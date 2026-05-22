package com.screenguard.ui.screens

import android.content.ComponentName
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.screenguard.R
import com.screenguard.service.AppBlockAccessibilityService
import com.screenguard.ui.components.AppUsageAdapter
import com.screenguard.utils.AppUsageInfo
import com.screenguard.utils.AppLimitManager
import com.screenguard.utils.UsageStatsHelper
import android.content.Intent
import java.util.concurrent.TimeUnit

class UsageFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var totalTimeText: TextView
    private lateinit var periodSpinner: Spinner
    private lateinit var emptyView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var changePasswordButton: Button

    private var currentPeriod = "today"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_usage, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.usage_recycler)
        totalTimeText = view.findViewById(R.id.total_time_text)
        periodSpinner = view.findViewById(R.id.period_spinner)
        emptyView = view.findViewById(R.id.empty_view)
        progressBar = view.findViewById(R.id.progress_bar)
        changePasswordButton = view.findViewById(R.id.change_password_button)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val periods = listOf("Today", "Last 7 Days", "Last 30 Days")
        val adapter = ArrayAdapter(requireContext(), R.layout.item_spinner_selected, periods)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        periodSpinner.adapter = adapter

        periodSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                currentPeriod = when (position) {
                    0 -> "today"
                    1 -> "week"
                    2 -> "month"
                    else -> "today"
                }
                loadStats()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        updateChangePasswordButton()
        changePasswordButton.setOnClickListener {
            showChangePasswordDialog()
        }

        loadStats()
    }

    private fun loadStats() {
        progressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.GONE

        // Run on a background thread because usage stats queries can be slow.
        Thread {
            val stats = UsageStatsHelper.getUsageStats(requireContext(), currentPeriod)
            val total = UsageStatsHelper.getTotalScreenTime(requireContext(), currentPeriod)

            requireActivity().runOnUiThread {
                progressBar.visibility = View.GONE

                if (stats.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    totalTimeText.text = "No data. Make sure Usage Access is granted."
                } else {
                    recyclerView.visibility = View.VISIBLE
                    recyclerView.adapter = AppUsageAdapter(
                        stats,
                        total,
                        limitProvider = { packageName ->
                            AppLimitManager.getLimit(requireContext(), packageName)
                        },
                        onSetLimit = { app ->
                            showSetLimitDialog(app)
                        }
                    )
                    totalTimeText.text = formatTime(total)
                }
            }
        }.start()
    }

    private fun showSetLimitDialog(app: AppUsageInfo) {
        val context = requireContext()
        val existingLimitMs = AppLimitManager.getLimit(context, app.packageName)
        val existingMinutes = TimeUnit.MILLISECONDS.toMinutes(existingLimitMs)
        val initialMinutes = (if (existingMinutes > 0L) existingMinutes else DEFAULT_LIMIT_MINUTES)
            .coerceAtLeast(MIN_LIMIT_MINUTES)
        val sliderMax = maxOf(MAX_LIMIT_MINUTES, initialMinutes)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 24, 42, 0)
        }
        val currentLimitText = TextView(context).apply {
            text = if (existingLimitMs > 0L) {
                "Current limit: ${formatClockLimit(existingMinutes)}"
            } else {
                "No limit set yet"
            }
            setTextColor(resources.getColor(R.color.text_secondary, null))
            textSize = 14f
        }
        val selectedLimitText = TextView(context).apply {
            text = formatClockLimit(initialMinutes)
            setTextColor(resources.getColor(R.color.text_primary, null))
            textSize = 32f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 24, 0, 0)
        }
        val selectedCaption = TextView(context).apply {
            text = "Daily limit"
            setTextColor(resources.getColor(R.color.text_secondary, null))
            textSize = 13f
            setPadding(0, 2, 0, 20)
        }
        val slider = SeekBar(context).apply {
            max = (sliderMax - MIN_LIMIT_MINUTES).toInt()
            progress = (initialMinutes - MIN_LIMIT_MINUTES).toInt()
            progressTintList = ColorStateList.valueOf(resources.getColor(R.color.accent_teal, null))
            progressBackgroundTintList = ColorStateList.valueOf(resources.getColor(R.color.slider_inactive, null))
            thumbTintList = ColorStateList.valueOf(resources.getColor(R.color.primary_purple, null))
            splitTrack = false
            setPadding(0, 10, 0, 10)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    selectedLimitText.text = formatClockLimit(progressToMinutes(progress))
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }

        container.addView(currentLimitText)
        container.addView(selectedLimitText)
        container.addView(selectedCaption)
        container.addView(slider)

        val passwordInput = if (!AppLimitManager.hasPassword(context)) {
            addPasswordField(container, "Create password")
        } else {
            null
        }

        AlertDialog.Builder(context)
            .setTitle(if (existingLimitMs > 0L) "Change limit for ${app.appName}" else "Set limit for ${app.appName}")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val minutes = progressToMinutes(slider.progress)
                        val password = passwordInput?.text?.toString().orEmpty()
                        passwordInput?.error = null

                        when {
                            passwordInput != null && password.length < 4 -> {
                                passwordInput.error = "Use at least 4 characters"
                            }
                            else -> {
                                if (passwordInput != null) AppLimitManager.setPassword(context, password)
                                AppLimitManager.setLimit(context, app.packageName, TimeUnit.MINUTES.toMillis(minutes))
                                AppLimitManager.seedTrackedUsage(
                                    context,
                                    app.packageName,
                                    UsageStatsHelper.getPackageUsageToday(context, app.packageName)
                                )
                                Toast.makeText(context, "Limit saved for ${app.appName}", Toast.LENGTH_SHORT).show()
                                updateChangePasswordButton()
                                promptForAccessibilityIfNeeded()
                                loadStats()
                                dialog.dismiss()
                            }
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun formatLimit(minutes: Long): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return when {
            hours > 0 && mins > 0 -> "${hours}h ${mins}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    private fun formatClockLimit(minutes: Long): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return "%02d:%02d".format(hours, mins)
    }

    private fun progressToMinutes(progress: Int): Long =
        MIN_LIMIT_MINUTES + progress.toLong()

    private fun updateChangePasswordButton() {
        changePasswordButton.visibility = if (AppLimitManager.hasPassword(requireContext())) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun showChangePasswordDialog() {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }

        val currentPasswordInput = addPasswordField(container, "Current password")
        val newPasswordInput = addPasswordField(container, "New password")
        val confirmPasswordInput = addPasswordField(container, "Confirm new password")

        AlertDialog.Builder(context)
            .setTitle("Change password")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val currentPassword = currentPasswordInput.text?.toString().orEmpty()
                        val newPassword = newPasswordInput.text?.toString().orEmpty()
                        val confirmPassword = confirmPasswordInput.text?.toString().orEmpty()

                        currentPasswordInput.error = null
                        newPasswordInput.error = null
                        confirmPasswordInput.error = null

                        when {
                            !AppLimitManager.verifyPassword(context, currentPassword) ->
                                currentPasswordInput.error = "Current password is wrong"
                            newPassword.length < 4 ->
                                newPasswordInput.error = "Use at least 4 characters"
                            newPassword != confirmPassword ->
                                confirmPasswordInput.error = "Passwords do not match"
                            else -> {
                                AppLimitManager.setPassword(context, newPassword)
                                Toast.makeText(context, "Password changed", Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                            }
                        }
                    }
                }
                dialog.show()
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
                topMargin = (14 * density).toInt()
            }
        }
        container.addView(field)
        return field
    }

    private fun promptForAccessibilityIfNeeded() {
        if (isAccessibilityServiceEnabled()) return

        AlertDialog.Builder(requireContext())
            .setTitle("Enable app blocking")
            .setMessage("Turn on ScreenGuard in Accessibility settings so limits can block apps as soon as they open.")
            .setNegativeButton("Later", null)
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(requireContext(), AppBlockAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            requireContext().contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun formatTime(ms: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    companion object {
        private const val MIN_LIMIT_MINUTES = 5L
        private const val MAX_LIMIT_MINUTES = 240L
        private const val DEFAULT_LIMIT_MINUTES = 30L
    }
}
