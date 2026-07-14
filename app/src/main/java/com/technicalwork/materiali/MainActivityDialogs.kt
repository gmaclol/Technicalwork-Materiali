package com.technicalwork.materiali

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

object MainActivityDialogs {

    /**
     * Dialog di benvenuto / modifica nome tecnico.
     */
    fun showTechnicianNameDialog(
        activity: Activity,
        isUpdate: Boolean,
        currentName: String?,
        onSave: (String) -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        val input = EditText(activity)
        if (isUpdate && currentName != null) {
            input.setText(currentName)
            input.setSelectAllOnFocus(true)
        }

        AlertDialog.Builder(activity)
            .setTitle(if (isUpdate) activity.getString(R.string.dialog_title_edit_tech_name) else activity.getString(R.string.dialog_title_welcome))
            .setMessage(activity.getString(R.string.dialog_msg_enter_tech_name))
            .setView(input)
            .setCancelable(isUpdate)
            .setPositiveButton(activity.getString(R.string.btn_save)) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    onSave(newName)
                } else if (!isUpdate) {
                    onCancel?.invoke()
                }
            }
            .apply {
                if (isUpdate) {
                    setNegativeButton(activity.getString(R.string.btn_cancel)) { dialog, _ ->
                        dialog.dismiss()
                    }
                }
            }
            .show()
    }

    /**
     * Dialog per la rinomina di un file Excel aziendale.
     */
    fun showRenameDialog(
        activity: Activity,
        currentFullName: String,
        onRename: (String) -> Unit
    ) {
        val currentName = currentFullName.substringBeforeLast('.')
        val input = EditText(activity)
        input.setText(currentName)
        input.setSelectAllOnFocus(true)

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.dialog_title_rename_file))
            .setMessage(activity.getString(R.string.dialog_msg_rename_file))
            .setView(input)
            .setPositiveButton(activity.getString(R.string.btn_rename)) { _, _ ->
                val newBaseName = input.text.toString().trim()
                if (newBaseName.isNotEmpty()) {
                    onRename(newBaseName)
                }
            }
            .setNegativeButton(activity.getString(R.string.btn_cancel), null)
            .show()
    }

    /**
     * Dialog per la prima configurazione di un appalto (Crea Nuovo o Seleziona Esistente).
     */
    fun showChoiceDialog(
        activity: Activity,
        company: String,
        onNewFile: () -> Unit,
        onSelectFile: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle(company)
            .setMessage(activity.getString(R.string.dialog_msg_config_company, company))
            .setPositiveButton("Nuovo file") { _, _ ->
                onNewFile()
            }
            .setNegativeButton(activity.getString(R.string.btn_use_existing)) { _, _ ->
                onSelectFile()
            }
            .setNeutralButton(activity.getString(R.string.btn_cancel), null)
            .show()
    }

    /**
     * Dialog per la configurazione del file dei materiali di consumo.
     */
    fun showConsumoChoiceDialog(
        activity: Activity,
        onNewFile: () -> Unit,
        onSelectFile: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle("Materiali di consumo")
            .setMessage("Configura il file per i Materiali di consumo")
            .setPositiveButton("Nuovo file") { _, _ ->
                onNewFile()
            }
            .setNegativeButton("Usa esistente") { _, _ ->
                onSelectFile()
            }
            .setNeutralButton(activity.getString(R.string.btn_cancel), null)
            .show()
    }

    /**
     * Dialog di conferma reset file Excel.
     */
    fun showResetConfirmationDialog(
        activity: Activity,
        onConfirm: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.dialog_title_reset_file))
            .setMessage(activity.getString(R.string.dialog_msg_reset_file))
            .setPositiveButton(activity.getString(R.string.btn_reset)) { _, _ ->
                onConfirm()
            }
            .setNegativeButton(activity.getString(R.string.btn_cancel), null)
            .show()
    }

    /**
     * Dialog di avviso per modifiche non salvate in fase di uscita.
     */
    fun showExitWarningDialog(
        activity: Activity,
        onExitWithoutSaving: () -> Unit,
        onSaveAndExit: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.dialog_title_unsaved_changes))
            .setMessage(activity.getString(R.string.dialog_msg_save_before_exit))
            .setNeutralButton(activity.getString(R.string.btn_exit_without_saving)) { _, _ ->
                onExitWithoutSaving()
            }
            .setNegativeButton(activity.getString(R.string.btn_no)) { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton(activity.getString(R.string.btn_save_and_exit)) { _, _ ->
                onSaveAndExit()
            }
            .show()
    }

    /**
     * Dialog per forzare l'uso del template corretto quando il formato del file importato non è valido.
     */
    fun showSampleDialog(
        activity: Activity,
        onUseTemplate: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.dialog_title_invalid_format))
            .setMessage(activity.getString(R.string.dialog_msg_invalid_format))
            .setPositiveButton(activity.getString(R.string.btn_use_template)) { _, _ ->
                onUseTemplate()
            }
            .setNegativeButton(activity.getString(R.string.btn_cancel), null)
            .show()
    }

    /**
     * Dialog di conferma eliminazione di una riga personalizzata dalla lista.
     */
    fun showDeleteConfirmation(
        activity: Activity,
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.dialog_title_delete_row))
            .setMessage(activity.getString(R.string.dialog_msg_delete_row))
            .setPositiveButton(activity.getString(R.string.btn_delete)) { _, _ ->
                onConfirm()
            }
            .setNegativeButton(activity.getString(R.string.btn_cancel)) { _, _ ->
                onCancel()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * BottomSheet per selezionare la modalità dello scambio materiali (Scanner o Generatore QR).
     */
    fun showExchangeChoiceSheet(
        activity: Activity,
        onGenerateQr: () -> Unit,
        onScanQr: () -> Unit
    ) {
        val dialog = BottomSheetDialog(activity)
        val inflater = activity.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.bottom_sheet_exchange_choice, null)

        view.findViewById<MaterialButton>(R.id.btnGenerateQr).setOnClickListener {
            dialog.dismiss()
            onGenerateQr()
        }

        view.findViewById<MaterialButton>(R.id.btnScanQr).setOnClickListener {
            dialog.dismiss()
            onScanQr()
        }

        dialog.setContentView(view)
        dialog.show()
    }
}
