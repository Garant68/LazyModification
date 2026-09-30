package com.example.lazymodification

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * Форма «Изменить интерфейс»: три действия —
 * 1) Перевести приложение (создать русскую локализацию),
 * 2) Удалить пункты меню,
 * 3) Вставить бегущую строку.
 */
class InterfaceToolsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APK_PATH = "com.example.lazymodification.INTERFACE_APK_PATH"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        setContentView(R.layout.activity_interface_tools)

        val apkPath = intent.getStringExtra(EXTRA_APK_PATH)
        if (apkPath == null) {
            Toast.makeText(this, getString(R.string.file_not_specified), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val file = File(apkPath)
        if (!file.exists()) {
            Toast.makeText(this, getString(R.string.file_not_exists), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        findViewById<TextView>(R.id.tvFileName).text = getString(R.string.file_label, file.name)

        findViewById<MaterialButton>(R.id.btnTranslate).setOnClickListener {
            startActivity(
                Intent(this, TranslateAppActivity::class.java)
                    .putExtra(EXTRA_APK_PATH, apkPath)
            )
        }
        findViewById<MaterialButton>(R.id.btnRemoveMenu).setOnClickListener {
            startActivity(
                Intent(this, InterfaceModifierActivity::class.java)
                    .putExtra(InterfaceModifierActivity.EXTRA_APK_PATH, apkPath)
                    .putExtra(InterfaceModifierActivity.EXTRA_MODE, InterfaceModifierActivity.MODE_MENU)
            )
        }
        findViewById<MaterialButton>(R.id.btnMarquee).setOnClickListener {
            startActivity(
                Intent(this, InterfaceModifierActivity::class.java)
                    .putExtra(InterfaceModifierActivity.EXTRA_APK_PATH, apkPath)
                    .putExtra(InterfaceModifierActivity.EXTRA_MODE, InterfaceModifierActivity.MODE_MARQUEE)
            )
        }
    }
}
