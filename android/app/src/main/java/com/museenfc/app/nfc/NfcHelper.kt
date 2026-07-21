package com.museenfc.app.nfc

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import java.io.IOException
import java.nio.charset.Charset

data class ScannedPatch(val tagUid: String, val checkpointCode: String?)

sealed class ProvisionResult {
    data class Success(val checkpointCode: String) : ProvisionResult()
    data class Failure(val reason: String) : ProvisionResult()
}

sealed class LockTestResult {
    data object Locked : LockTestResult()
    data object NotLocked : LockTestResult()
    data class Error(val reason: String) : LockTestResult()
}

/**
 * Toute la logique NFC bas niveau : lecture d'UID/UUID applicatif, écriture au provisioning,
 * et verrouillage par mot de passe (R1 : "verrouiller / protéger par mot de passe après
 * provisioning"). Cible les patchs NXP NTAG215 utilisés pour ce POC (confirmé via NFC Tools :
 * 135 pages, 540 octets — cf. capture d'écran de recette).
 *
 * NTAG213/215/216 partagent le même jeu de commandes et la même disposition de registres
 * (MIRROR/RFUI/MIRROR_PAGE/AUTH0 puis ACCESS/RFUI×3 puis PWD puis PACK), mais PAS les mêmes
 * numéros de page : ces 4 dernières pages de la puce dépendent de sa taille mémoire. Si vous
 * changez de référence de patch, les 4 constantes CFG0/CFG1/PWD/PACK_PAGE ci-dessous doivent
 * être adaptées (NTAG213 : 0x29/0x2A/0x2B/0x2C ; NTAG216 : 0xE3/0xE4/0xE5/0xE6).
 */
object NfcHelper {

    // Adresse mémoire (en octets) où commence la zone verrouillable par mot de passe.
    // Nos messages NDEF (petit texte, l'UUID checkpointCode) tiennent largement avant la page 12 ;
    // AUTH0 = page à partir de laquelle l'authentification est exigée pour ÉCRIRE.
    private const val AUTH0_PAGE = 12
    private const val CFG0_PAGE = 0x83 // page 131 (NTAG215) : MIRROR / RFUI / MIRROR_PAGE / AUTH0
    private const val CFG1_PAGE = 0x84 // page 132 (NTAG215) : ACCESS (PROT, CFGLCK, AUTHLIM) / RFUI x3
    private const val PWD_PAGE = 0x85  // page 133 (NTAG215) : mot de passe (4 octets)
    private const val PACK_PAGE = 0x86 // page 134 (NTAG215) : PACK (2 octets) + RFUI

    // Délai d'attente par échange bas niveau. Le défaut Android (souvent ~600 ms selon les
    // téléphones) suffit pour un aller-retour isolé, mais provisionAndLock/testLock en enchaînent
    // plusieurs à la suite : un patch légèrement mal couplé fait alors échouer un échange au
    // milieu de la séquence. On l'allonge pour tolérer un couplage NFC imparfait.
    private const val TRANSCEIVE_TIMEOUT_MS = 2000

    fun readUid(tag: Tag): String =
        tag.id.joinToString("") { "%02X".format(it) }

    /** Lit le checkpointCode écrit au provisioning (premier record NDEF texte), s'il existe déjà. */
    fun readCheckpointCode(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        return try {
            ndef.connect()
            val message = ndef.ndefMessage ?: return null
            val record = message.records.firstOrNull() ?: return null
            parseTextRecord(record)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { ndef.close() }
        }
    }

    fun scan(tag: Tag): ScannedPatch = ScannedPatch(readUid(tag), readCheckpointCode(tag))

    /**
     * Provisioning d'un patch neuf : écrit le checkpointCode en NDEF puis pose la protection
     * par mot de passe sur les pages suivantes. Après cette opération, réécrire le patch sans
     * connaître [password] est refusé par la puce elle-même (démontrable en direct : R1).
     * La lecture reste libre (PROT=0) : un gardien doit pouvoir lire le patch sans mot de passe
     * pour scanner, seule l'écriture est protégée.
     */
    fun provisionAndLock(tag: Tag, checkpointCode: String, password: ByteArray, pack: ByteArray): ProvisionResult {
        require(password.size == 4) { "Le mot de passe NTAG21x fait exactement 4 octets" }
        require(pack.size == 2) { "Le PACK NTAG21x fait exactement 2 octets" }

        val ndef = Ndef.get(tag)
            ?: return ProvisionResult.Failure("Ce patch ne supporte pas NDEF (mauvais type de puce ?)")

        return try {
            ndef.connect()
            val record = NdefRecord.createTextRecord("en", checkpointCode)
            ndef.writeNdefMessage(NdefMessage(arrayOf(record)))
            ndef.close()

            val nfcA = NfcA.get(tag)
                ?: return ProvisionResult.Failure("Puce non compatible NfcA : verrouillage impossible")
            nfcA.connect()
            nfcA.timeout = TRANSCEIVE_TIMEOUT_MS
            try {
                // Ordre important : tant qu'AUTH0 (CFG0) n'a pas été abaissé, les pages de
                // config restent en écriture libre. On écrit donc PWD/PACK/CFG1 EN PREMIER,
                // et CFG0 EN DERNIER — sans quoi la puce s'auto-verrouille avant qu'on ait pu
                // finir d'écrire son propre mot de passe (poule et œuf).
                writePage(nfcA, PWD_PAGE, password)
                writePage(nfcA, PACK_PAGE, pack + byteArrayOf(0x00, 0x00))
                // CFG1 : ACCESS = 0x00 -> PROT=0 (protège l'écriture uniquement, pas la lecture)
                writePage(nfcA, CFG1_PAGE, byteArrayOf(0x00, 0x00, 0x00, 0x00))
                // CFG0 en dernier : MIRROR=0x00, RFUI=0x00, MIRROR_PAGE=0x00, AUTH0=AUTH0_PAGE.
                // C'est cette écriture qui active réellement la protection.
                writePage(nfcA, CFG0_PAGE, byteArrayOf(0x00, 0x00, 0x00, AUTH0_PAGE.toByte()))

                // Relecture de contrôle : certains contrôleurs NFC Android n'exposent pas le NAK
                // de la puce comme une exception (transceive renvoie normalement), donc une écriture
                // qui n'a pas pris se déclarait "réussie" à tort. On confirme sur la puce elle-même
                // que AUTH0 a bien la valeur voulue avant d'annoncer un succès.
                val cfg0Readback = readPage(nfcA, CFG0_PAGE)
                if (cfg0Readback[3] != AUTH0_PAGE.toByte()) {
                    return ProvisionResult.Failure(
                        "Verrouillage non confirmé par la puce (AUTH0 pas appliqué), réessayez",
                    )
                }
            } finally {
                runCatching { nfcA.close() }
            }

            ProvisionResult.Success(checkpointCode)
        } catch (e: TagLostException) {
            ProvisionResult.Failure("Patch retiré trop tôt, recommencez le provisioning")
        } catch (e: FormatException) {
            ProvisionResult.Failure("Format NDEF refusé par la puce")
        } catch (e: IOException) {
            ProvisionResult.Failure("Écriture refusée par la puce (déjà verrouillée ?)")
        } catch (e: SecurityException) {
            // "Tag is out of date" : le Tag Android a été gardé trop longtemps sans I/O (ex.
            // attente réseau) et le système en a révoqué l'accès. Non rattrapée, cette exception
            // plantait l'appli. On demande de retaper plutôt que de crasher.
            ProvisionResult.Failure("Le patch n'était plus valide (retiré/reposé trop tard), réessayez")
        }
    }

    /**
     * Tente une authentification par mot de passe (nécessaire avant de modifier un patch
     * déjà verrouillé, par ex. pour le déprovisionner). Renvoie false si le mot de passe
     * est incorrect ou si la puce n'est pas protégée par mot de passe.
     */
    fun authenticate(tag: Tag, password: ByteArray): Boolean {
        val nfcA = NfcA.get(tag) ?: return false
        return try {
            nfcA.connect()
            nfcA.timeout = TRANSCEIVE_TIMEOUT_MS
            // Commande PWD_AUTH (0x1B) + mot de passe 4 octets -> renvoie 2 octets PACK si succès.
            val response = nfcA.transceive(byteArrayOf(0x1B.toByte()) + password)
            response.size == 2
        } catch (e: Exception) {
            false
        } finally {
            runCatching { nfcA.close() }
        }
    }

    /**
     * Démo de sécurité (R1) : tente une écriture sans authentification préalable directement sur
     * la première page protégée (AUTH0_PAGE), sans passer par l'API NDEF haut niveau.
     *
     * Pourquoi pas une réécriture NDEF complète comme avant : un message de test plus court que
     * le checkpointCode d'origine (ex. "tentative-sabotage") tient entièrement dans les pages
     * libres SOUS AUTH0 et n'atteint donc jamais la zone protégée — le test réussissait par
     * construction sans rien prouver, et corrompait en prime le patch en écrasant son vrai
     * contenu sans le restaurer. Ici on cible la page frontière elle-même, on sauvegarde son
     * contenu avant le test et on le restaure aussitôt si l'écriture est passée : le patch
     * ressort inchangé quel que soit le résultat du test.
     */
    fun testLock(tag: Tag): LockTestResult {
        val nfcA = NfcA.get(tag) ?: return LockTestResult.Error("Puce non compatible NfcA")
        return try {
            nfcA.connect()
            nfcA.timeout = TRANSCEIVE_TIMEOUT_MS
            val original = readPage(nfcA, AUTH0_PAGE)
            val decoy = byteArrayOf(0x53, 0x41, 0x42, 0x00) // valeur de test neutre, jamais persistée
            val writeAccepted = try {
                writePage(nfcA, AUTH0_PAGE, decoy)
                true
            } catch (e: IOException) {
                false // refus explicite de la puce -> verrouillage effectif
            }
            if (!writeAccepted) {
                LockTestResult.Locked
            } else {
                val after = readPage(nfcA, AUTH0_PAGE)
                if (after.contentEquals(original)) {
                    // Écriture acceptée par le contrôleur mais la puce a ignoré le NAK sans
                    // exception : la page n'a en fait pas bougé -> verrouillage effectif.
                    LockTestResult.Locked
                } else {
                    writePage(nfcA, AUTH0_PAGE, original) // restauration immédiate
                    LockTestResult.NotLocked
                }
            }
        } catch (e: TagLostException) {
            LockTestResult.Error("Patch retiré pendant le test, réessayez")
        } catch (e: IOException) {
            LockTestResult.Error("Erreur de communication avec la puce, réessayez")
        } catch (e: SecurityException) {
            LockTestResult.Error("Le patch n'était plus valide (retiré/reposé trop tard), réessayez")
        } finally {
            runCatching { nfcA.close() }
        }
    }

    private fun writePage(nfcA: NfcA, page: Int, data: ByteArray) {
        require(data.size == 4) { "Une page NTAG21x fait 4 octets" }
        // Commande WRITE (0xA2) : 1 octet commande + 1 octet n° de page + 4 octets de données.
        transceiveRetrying(nfcA, byteArrayOf(0xA2.toByte(), page.toByte()) + data)
    }

    private fun readPage(nfcA: NfcA, page: Int): ByteArray {
        // Commande READ (0x30) : 1 octet commande + 1 octet n° de page -> renvoie normalement
        // 16 octets (4 pages consécutives) ; on ne garde que les 4 octets de la page demandée.
        val response = transceiveRetrying(nfcA, byteArrayOf(0x30.toByte(), page.toByte()))
        // Une réponse plus courte que prévu (NAK renvoyé comme donnée au lieu d'une exception,
        // selon le contrôleur NFC du téléphone) ne doit pas planter l'appli avec une
        // IndexOutOfBoundsException non rattrapée : on la traite comme un échec de lecture normal.
        if (response.size < 4) {
            throw IOException("Réponse de la puce trop courte (${response.size} octet(s)) pour la page $page")
        }
        return response.copyOfRange(0, 4)
    }

    /**
     * Un seul réessai en cas d'IOException (bruit/couplage passager) avant d'abandonner : un
     * TagLostException n'est PAS réessayé, le patch n'étant physiquement plus là, retenter
     * immédiatement ne peut que renvoyer la même erreur.
     */
    private fun transceiveRetrying(nfcA: NfcA, command: ByteArray): ByteArray = try {
        nfcA.transceive(command)
    } catch (e: TagLostException) {
        throw e
    } catch (e: IOException) {
        Thread.sleep(80)
        nfcA.transceive(command)
    }

    private fun parseTextRecord(record: NdefRecord): String? {
        if (record.tnf != NdefRecord.TNF_WELL_KNOWN || !record.type.contentEquals(NdefRecord.RTD_TEXT)) return null
        val payload = record.payload
        if (payload.isEmpty()) return null
        val languageCodeLength = payload[0].toInt() and 0x3F
        val isUtf16 = (payload[0].toInt() and 0x80) != 0
        val charset = if (isUtf16) Charset.forName("UTF-16") else Charsets.UTF_8
        return String(payload, 1 + languageCodeLength, payload.size - 1 - languageCodeLength, charset)
    }
}
