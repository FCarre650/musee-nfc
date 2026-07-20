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

/**
 * Toute la logique NFC bas niveau : lecture d'UID/UUID applicatif, écriture au provisioning,
 * et verrouillage par mot de passe (R1 : "verrouiller / protéger par mot de passe après
 * provisioning"). Basé sur les commandes NTAG213/215/216 (famille MIFARE Ultralight EV1),
 * les patchs les plus courants et les moins chers pour ce cas d'usage.
 *
 * Non testé sur puce physique dans cet environnement (pas de device/lecteur NFC disponible
 * ici) : à valider au premier palier NFC réel du plan de test (voir PLAN_TEST_PROGRESSIF.md).
 * Les numéros de page et la disposition des registres viennent de la datasheet NXP NTAG213.
 */
object NfcHelper {

    // Adresse mémoire (en octets) où commence la zone verrouillable par mot de passe.
    // Nos messages NDEF (petit texte, l'UUID checkpointCode) tiennent largement avant la page 12 ;
    // AUTH0 = page à partir de laquelle l'authentification est exigée pour ÉCRIRE.
    private const val AUTH0_PAGE = 12
    private const val CFG0_PAGE = 0x29 // page 41 : MIRROR / RFUI / MIRROR_PAGE / AUTH0
    private const val CFG1_PAGE = 0x2A // page 42 : ACCESS (PROT, CFGLCK, AUTHLIM) / RFUI x3
    private const val PWD_PAGE = 0x2B  // page 43 : mot de passe (4 octets)
    private const val PACK_PAGE = 0x2C // page 44 : PACK (2 octets) + RFUI

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
            // Commande PWD_AUTH (0x1B) + mot de passe 4 octets -> renvoie 2 octets PACK si succès.
            val response = nfcA.transceive(byteArrayOf(0x1B.toByte()) + password)
            response.size == 2
        } catch (e: Exception) {
            false
        } finally {
            runCatching { nfcA.close() }
        }
    }

    /** Démo de sécurité : tente une réécriture NDEF sans authentification préalable.
     *  Sur un patch verrouillé (provisionné via [provisionAndLock]), ceci doit échouer. */
    fun attemptUnauthorizedRewrite(tag: Tag, payload: String): Boolean {
        val ndef = Ndef.get(tag) ?: return false
        return try {
            ndef.connect()
            ndef.writeNdefMessage(NdefMessage(arrayOf(NdefRecord.createTextRecord("en", payload))))
            true // écriture acceptée -> le patch n'était PAS verrouillé
        } catch (e: Exception) {
            false // refusé par la puce -> comportement attendu sur un patch verrouillé
        } finally {
            runCatching { ndef.close() }
        }
    }

    private fun writePage(nfcA: NfcA, page: Int, data: ByteArray) {
        require(data.size == 4) { "Une page NTAG21x fait 4 octets" }
        // Commande WRITE (0xA2) : 1 octet commande + 1 octet n° de page + 4 octets de données.
        nfcA.transceive(byteArrayOf(0xA2.toByte(), page.toByte()) + data)
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
