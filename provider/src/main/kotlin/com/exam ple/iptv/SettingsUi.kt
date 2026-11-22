package com.example.iptv

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

// UI dựng bằng code (AlertDialog của framework) nên plugin không cần resource/layout.
object SettingsUi {
    private fun toast(ctx: Context, msg: String) =
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun label(c: CustomChannel) = buildString {
        append(c.name).append("  (").append(c.group).append(")")
        if (!c.licenseKey.isNullOrBlank()) append("  • ClearKey")
    }

    fun showMenu(ctx: Context, store: CustomChannelStore) {
        val n = store.all().size
        val items = arrayOf("Thêm kênh", "Danh sách kênh ($n)", "Xóa kênh")
        AlertDialog.Builder(ctx)
            .setTitle("IPTV M3U")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showAdd(ctx, store)
                    1 -> showList(ctx, store)
                    2 -> showDelete(ctx, store)
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showAdd(ctx: Context, store: CustomChannelStore) {
        val hints = listOf(
            "Tên kênh *",
            "Link stream (http/https) *",
            "Nhóm (trống = ${CustomChannelStore.DEFAULT_GROUP})",
            "Link logo (tùy chọn)",
            "ClearKey: kid:key, JSON hoặc URL license (tùy chọn)",
            "User-Agent (tùy chọn)",
            "Referer (tùy chọn)",
        )
        val uriFields = setOf(1, 3, 6)
        val fields = hints.mapIndexed { i, h ->
            EditText(ctx).apply {
                hint = h
                inputType = InputType.TYPE_CLASS_TEXT or
                    if (i in uriFields) InputType.TYPE_TEXT_VARIATION_URI
                    else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0)
            fields.forEach { addView(it) }
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Thêm kênh")
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Lưu", null) // gán listener riêng bên dưới để lỗi nhập không đóng dialog
            .setNegativeButton("Hủy", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = fields.map { f -> f.text.toString().trim() }
                val name = v[0]
                val url = v[1]
                val license = v[4]
                when {
                    name.isEmpty() || url.isEmpty() -> toast(ctx, "Cần nhập tên kênh và link")
                    !url.startsWith("http", ignoreCase = true) -> toast(ctx, "Link phải bắt đầu bằng http hoặc https")
                    license.isNotEmpty() && ClearKeyUtil.parse(license) == null ->
                        toast(ctx, "ClearKey không đọc được. Dùng kid:key (hex), JSON hoặc URL license")
                    else -> {
                        val added = store.add(
                            CustomChannel(
                                name = name,
                                url = url,
                                group = v[2].ifEmpty { CustomChannelStore.DEFAULT_GROUP },
                                logo = v[3].ifEmpty { null },
                                licenseKey = license.ifEmpty { null },
                                userAgent = v[5].ifEmpty { null },
                                referer = v[6].ifEmpty { null },
                            )
                        )
                        if (added) {
                            toast(ctx, "Đã thêm kênh")
                            dialog.dismiss()
                        } else {
                            toast(ctx, "Link này đã có trong danh sách")
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showList(ctx: Context, store: CustomChannelStore) {
        val list = store.all()
        if (list.isEmpty()) {
            toast(ctx, "Chưa có kênh nào")
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle("Danh sách kênh (${list.size})")
            .setItems(list.map { label(it) }.toTypedArray()) { _, i ->
                val c = list[i]
                val detail = buildString {
                    append("Nhóm: ").append(c.group).append("\n")
                    append("Link: ").append(c.url).append("\n")
                    append("ClearKey: ").append(if (c.licenseKey.isNullOrBlank()) "không" else "có")
                    c.logo?.let { append("\nLogo: ").append(it) }
                    c.userAgent?.let { append("\nUser-Agent: ").append(it) }
                    c.referer?.let { append("\nReferer: ").append(it) }
                }
                AlertDialog.Builder(ctx)
                    .setTitle(c.name)
                    .setMessage(detail)
                    .setPositiveButton("Đóng", null)
                    .show()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showDelete(ctx: Context, store: CustomChannelStore) {
        val list = store.all()
        if (list.isEmpty()) {
            toast(ctx, "Chưa có kênh nào để xóa")
            return
        }
        val checked = BooleanArray(list.size)
        AlertDialog.Builder(ctx)
            .setTitle("Chọn kênh cần xóa")
            .setMultiChoiceItems(list.map { label(it) }.toTypedArray(), checked) { _, i, isChecked ->
                checked[i] = isChecked
            }
            .setPositiveButton("Xóa") { _, _ ->
                val idx = checked.indices.filter { checked[it] }.toSet()
                if (idx.isEmpty()) {
                    toast(ctx, "Chưa chọn kênh nào")
                } else {
                    confirm(ctx, "Xóa ${idx.size} kênh đã chọn?") {
                        store.removeAt(idx)
                        toast(ctx, "Đã xóa ${idx.size} kênh")
                    }
                }
            }
            .setNeutralButton("Xóa tất cả") { _, _ ->
                confirm(ctx, "Xóa toàn bộ ${list.size} kênh?") {
                    store.clear()
                    toast(ctx, "Đã xóa tất cả")
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun confirm(ctx: Context, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(ctx)
            .setMessage(message)
            .setPositiveButton("Đồng ý") { _, _ -> onYes() }
            .setNegativeButton("Hủy", null)
            .show()
    }
}
