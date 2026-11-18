package io.legado.app.ui.book.private

import android.text.InputType
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.R
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogMultipleEditTextBinding
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.privacy.PrivateShelfActions
import io.legado.app.help.privacy.PrivateShelfVerifyResult
import io.legado.app.help.privacy.encodePrivateShelfPassword
import io.legado.app.help.privacy.isValidPrivateShelfPassword
import io.legado.app.help.privacy.verifyPrivateShelfPassword
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Every entry into the private shelf goes through this gate. */
object PrivateShelfGate {

    fun require(
        activity: FragmentActivity,
        onCancelled: (() -> Unit)? = null,
        onAuthorized: () -> Unit,
    ) {
        val stored = LocalConfig.privateShelfPassword
        if (stored.isNullOrBlank()) {
            showSetPassword(activity, onCancelled, onAuthorized)
        } else {
            showVerifyPassword(activity, onCancelled, onAuthorized)
        }
    }

    private fun showSetPassword(
        activity: FragmentActivity,
        onCancelled: (() -> Unit)?,
        onAuthorized: () -> Unit,
    ) {
        val binding = DialogMultipleEditTextBinding.inflate(activity.layoutInflater)
        binding.layout2.visibility = android.view.View.VISIBLE
        binding.edit1.hint = activity.getString(R.string.private_shelf_password_hint)
        binding.edit2.hint = activity.getString(R.string.private_shelf_confirm_hint)
        listOf(binding.edit1, binding.edit2).forEach {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        binding.layout1.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        binding.layout2.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE

        val dialog = activity.alert(
            titleResource = R.string.private_shelf_set_password,
            messageResource = R.string.private_shelf_password_rule,
        ) {
            customView { binding.root }
            okButton()
            cancelButton { onCancelled?.invoke() }
        }
        dialog.setOnCancelListener { onCancelled?.invoke() }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val password = binding.edit1.text.toString()
            val confirm = binding.edit2.text.toString()
            binding.layout1.error = null
            binding.layout2.error = null
            when {
                !isValidPrivateShelfPassword(password) -> {
                    binding.layout1.error = activity.getString(R.string.private_shelf_password_invalid)
                }

                password != confirm -> {
                    binding.layout2.error = activity.getString(R.string.private_shelf_password_mismatch)
                }

                else -> {
                    it.isEnabled = false
                    activity.lifecycleScope.launch {
                        val encoded = withContext(Default) { encodePrivateShelfPassword(password) }
                        LocalConfig.privateShelfPassword = encoded
                        dialog.dismiss()
                        onAuthorized()
                    }
                }
            }
        }
    }

    private fun showVerifyPassword(
        activity: FragmentActivity,
        onCancelled: (() -> Unit)?,
        onAuthorized: () -> Unit,
    ) {
        val binding = DialogEditTextBinding.inflate(activity.layoutInflater)
        binding.editView.hint = activity.getString(R.string.private_shelf_password_hint)
        binding.editView.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        binding.editView.parent?.let {
            if (it is TextInputLayout) it.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }

        val dialog = activity.alert(
            titleResource = R.string.private_shelf,
            messageResource = R.string.private_shelf_password_hint,
        ) {
            customView { binding.root }
            okButton()
            cancelButton { onCancelled?.invoke() }
            neutralButton(activity.getString(R.string.private_shelf_forgot)) {
                confirmReset(activity, onCancelled, onAuthorized)
            }
        }
        dialog.setOnCancelListener { onCancelled?.invoke() }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val password = binding.editView.text.toString()
            val stored = LocalConfig.privateShelfPassword
            it.isEnabled = false
            activity.lifecycleScope.launch {
                val result = withContext(Default) {
                    verifyPrivateShelfPassword(password, stored)
                }
                when (result) {
                    PrivateShelfVerifyResult.MATCH -> {
                        dialog.dismiss()
                        onAuthorized()
                    }

                    PrivateShelfVerifyResult.MISMATCH -> {
                        binding.editView.error =
                            activity.getString(R.string.private_shelf_password_wrong)
                        it.isEnabled = true
                    }

                    PrivateShelfVerifyResult.CORRUPT,
                    PrivateShelfVerifyResult.NOT_SET,
                    -> {
                        it.isEnabled = true
                        activity.toastOnUi(R.string.private_shelf_reset)
                        confirmReset(activity, onCancelled, onAuthorized)
                    }
                }
            }
        }
    }

    private fun confirmReset(
        activity: FragmentActivity,
        onCancelled: (() -> Unit)?,
        onAuthorized: () -> Unit,
    ) {
        activity.alert(
            titleResource = R.string.private_shelf_reset,
            messageResource = R.string.private_shelf_reset_confirm,
        ) {
            okButton {
                activity.lifecycleScope.launch {
                    PrivateShelfActions.resetAllAndClearPassword()
                    activity.toastOnUi(R.string.private_shelf_reset_done)
                    showSetPassword(activity, onCancelled, onAuthorized)
                }
            }
            cancelButton { onCancelled?.invoke() }
        }
    }
}
