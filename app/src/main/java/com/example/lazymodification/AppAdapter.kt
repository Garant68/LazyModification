package com.example.lazymodification

import android.content.pm.ApplicationInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val apps: List<ApplicationInfo>,
    private val onClick: (ApplicationInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        val tvName: TextView = view.findViewById(R.id.tvAppName)
        val tvPackage: TextView = view.findViewById(R.id.tvPackageName)
        val tvInfo: TextView = view.findViewById(R.id.tvAppInfo)
        val tvSize: TextView = view.findViewById(R.id.tvSize)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        val pm = holder.itemView.context.packageManager

        holder.tvName.text = pm.getApplicationLabel(app).toString()
        holder.tvPackage.text = app.packageName
        holder.ivIcon.setImageDrawable(pm.getApplicationIcon(app))

        // Определяем, split это или обычный APK
        val splits = app.splitSourceDirs
        val isSplit = !splits.isNullOrEmpty()

        if (isSplit) {
            val totalSplits = splits.size + 1 // base + splits
            holder.tvInfo.text = holder.itemView.context.getString(R.string.split_apk_files, totalSplits)
            holder.tvInfo.visibility = View.VISIBLE
        } else {
            holder.tvInfo.visibility = View.GONE
        }

        // Размер
        val sourceFile = java.io.File(app.sourceDir)
        var totalSize = sourceFile.length()
        if (isSplit) {
            splits.forEach { totalSize += java.io.File(it).length() }
        }
        holder.tvSize.text = formatSize(totalSize)

        holder.itemView.setOnClickListener { onClick(app) }
    }

    override fun getItemCount() = apps.size

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}