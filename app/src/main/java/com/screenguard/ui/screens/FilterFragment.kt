package com.screenguard.ui.screens

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.screenguard.R
import com.screenguard.ui.MainActivity
import com.screenguard.ui.components.BlocklistAdapter
import com.screenguard.utils.AppLimitManager
import com.screenguard.utils.BlocklistManager

class FilterFragment : Fragment() {

    private lateinit var filterSwitch: SwitchMaterial
    private lateinit var statusText: TextView
    private lateinit var blocklistRecycler: RecyclerView
    private lateinit var addDomainBtn: Button
    private var ignoreSwitchChanges = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_filter, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        filterSwitch = view.findViewById(R.id.filter_switch)
        statusText = view.findViewById(R.id.filter_status_text)
        blocklistRecycler = view.findViewById(R.id.blocklist_recycler)
        addDomainBtn = view.findViewById(R.id.add_domain_btn)

        blocklistRecycler.layoutManager = LinearLayoutManager(requireContext())

        setSwitchCheckedSilently(BlocklistManager.isFilterEnabled(requireContext()))
        updateStatusText(filterSwitch.isChecked)

        filterSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (ignoreSwitchChanges) return@setOnCheckedChangeListener

            if (isChecked) {
                if (AppLimitManager.hasPassword(requireContext())) {
                    setFilterEnabled(true)
                } else {
                    setSwitchCheckedSilently(false)
                    showCreatePasswordForFilterDialog()
                }
            } else {
                if (AppLimitManager.hasPassword(requireContext())) {
                    setSwitchCheckedSilently(true)
                    showDisableFilterDialog()
                } else {
                    setFilterEnabled(false)
                }
            }
        }

        addDomainBtn.setOnClickListener { showAddDomainDialog() }

        loadBlocklist()
    }

    private fun updateStatusText(enabled: Boolean) {
        statusText.text = if (enabled) {
            "Content filter is active. Blocked domains are being filtered."
        } else {
            "Content filter is off."
        }
        statusText.setTextColor(
            resources.getColor(
                if (enabled) R.color.success_text else R.color.muted_text,
                null
            )
        )
        statusText.setBackgroundResource(
            if (enabled) R.drawable.bg_status_active else R.drawable.bg_status_inactive
        )
    }

    private fun setFilterEnabled(enabled: Boolean) {
        BlocklistManager.setFilterEnabled(requireContext(), enabled)
        setSwitchCheckedSilently(enabled)
        updateStatusText(enabled)

        val activity = requireActivity() as MainActivity
        if (enabled) {
            activity.requestVpnPermission()
        } else {
            activity.stopVpn()
        }
    }

    private fun setSwitchCheckedSilently(checked: Boolean) {
        ignoreSwitchChanges = true
        filterSwitch.isChecked = checked
        ignoreSwitchChanges = false
    }

    private fun showCreatePasswordForFilterDialog() {
        val container = dialogContainer(
            "A password is required before the filter can be turned off."
        )
        val input = addPasswordField(container, "Create password")

        AlertDialog.Builder(requireContext())
            .setTitle("Create password")
            .setView(container)
            .setPositiveButton("Enable", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val password = input.text?.toString().orEmpty()
                        input.error = null
                        if (password.length < 4) {
                            input.error = "Use at least 4 characters"
                            return@setOnClickListener
                        }

                        AppLimitManager.setPassword(requireContext(), password)
                        setFilterEnabled(true)
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun showDisableFilterDialog() {
        val container = dialogContainer(
            "Enter the password to turn off content filtering."
        )
        val input = addPasswordField(container, "Password")

        AlertDialog.Builder(requireContext())
            .setTitle("Turn off filter")
            .setView(container)
            .setPositiveButton("Turn off", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val password = input.text?.toString().orEmpty()
                        input.error = null
                        if (AppLimitManager.verifyPassword(requireContext(), password)) {
                            setFilterEnabled(false)
                            dialog.dismiss()
                        } else {
                            input.error = "Wrong password"
                        }
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        setSwitchCheckedSilently(true)
                        dialog.dismiss()
                    }
                }
                dialog.setOnCancelListener {
                    setSwitchCheckedSilently(true)
                }
                dialog.show()
            }
    }

    private fun dialogContainer(message: String): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 12, 48, 0)
            addView(TextView(requireContext()).apply {
                text = message
                setTextColor(resources.getColor(R.color.text_secondary, null))
                textSize = 14f
                setPadding(0, 0, 0, 12)
            })
        }
    }

    private fun addPasswordField(container: LinearLayout, hintText: String): EditText {
        val density = resources.displayMetrics.density
        val field = EditText(requireContext()).apply {
            hint = hintText
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            maxLines = 1
            setSingleLine(true)
            setTextColor(resources.getColor(R.color.text_primary, null))
            setHintTextColor(resources.getColor(R.color.text_secondary, null))
            minHeight = (56 * density).toInt()
            setPadding(
                (12 * density).toInt(),
                (10 * density).toInt(),
                (12 * density).toInt(),
                (6 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (10 * density).toInt()
            }
        }
        container.addView(field)
        return field
    }

    private fun loadBlocklist() {
        val domains = BlocklistManager.getCustomBlocked(requireContext()).sorted()
        blocklistRecycler.adapter = BlocklistAdapter(domains.toMutableList()) { domain ->
            BlocklistManager.removeCustomBlocked(requireContext(), domain)
            loadBlocklist()
        }
    }

    private fun showAddDomainDialog() {
        val input = EditText(requireContext()).apply {
            hint = "e.g. example.com"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Block a domain")
            .setView(input)
            .setPositiveButton("Block") { _, _ ->
                val domain = input.text.toString().trim()
                if (domain.isNotEmpty()) {
                    BlocklistManager.addCustomBlocked(requireContext(), domain)
                    Toast.makeText(requireContext(), "$domain added to blocklist", Toast.LENGTH_SHORT).show()
                    loadBlocklist()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
