/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package common.widget

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.support.v13.view.inputmethod.EditorInfoCompat
import android.support.v13.view.inputmethod.InputConnectionCompat
import android.support.v13.view.inputmethod.InputContentInfoCompat
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import com.google.android.mms.ContentType
import com.moez.QKSMS.R
import com.uber.autodispose.android.scope
import com.uber.autodispose.kotlin.autoDisposable
import common.util.Colors
import common.util.FontProvider
import common.widget.QkTextView.Companion.SIZE_PRIMARY
import common.widget.QkTextView.Companion.SIZE_SECONDARY
import common.widget.QkTextView.Companion.SIZE_TERTIARY
import common.widget.QkTextView.Companion.SIZE_TOOLBAR
import injection.appComponent
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import util.Preferences
import util.tryOrNull
import javax.inject.Inject


/**
 * Custom implementation of EditText to allow for dynamic text colors
 *
 * Beware of updating to extend AppCompatTextView, as this inexplicably breaks the view in
 * the contacts chip view
 */
class QkEditText @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : EditText(context, attrs) {

    companion object {
        const val COLOR_PRIMARY = 0
        const val COLOR_SECONDARY = 1
        const val COLOR_TERTIARY = 2
        const val COLOR_PRIMARY_ON_THEME = 3
        const val COLOR_SECONDARY_ON_THEME = 4
        const val COLOR_TERTIARY_ON_THEME = 5

        const val SIZE_PRIMARY = 0
        const val SIZE_SECONDARY = 1
        const val SIZE_TERTIARY = 2
        const val SIZE_TOOLBAR = 3
    }

    @Inject lateinit var colors: Colors
    @Inject lateinit var fontProvider: FontProvider
    @Inject lateinit var prefs: Preferences

    val backspaces: Subject<Unit> = PublishSubject.create()
    val inputContentSelected: Subject<InputContentInfoCompat> = PublishSubject.create()
    var supportsInputContent: Boolean = false

    private var textColorObservable: Observable<Int>? = null
    private var textColorHintObservable: Observable<Int>? = null

    init {
        if (!isInEditMode) {
            appComponent.inject(this)

            context.obtainStyledAttributes(attrs, R.styleable.QkEditText)?.run {
                val colorAttr = getInt(R.styleable.QkEditText_textColor, -1)
                val colorHintAttr = getInt(R.styleable.QkEditText_textColorHint, -1)
                val textSizeAttr = getInt(R.styleable.QkEditText_textSize, -1)

                textColorObservable = when (colorAttr) {
                    COLOR_PRIMARY -> colors.textPrimary
                    COLOR_SECONDARY -> colors.textSecondary
                    COLOR_TERTIARY -> colors.textTertiary
                    COLOR_PRIMARY_ON_THEME -> colors.textPrimaryOnTheme
                    COLOR_SECONDARY_ON_THEME -> colors.textSecondaryOnTheme
                    COLOR_TERTIARY_ON_THEME -> colors.textTertiaryOnTheme
                    else -> null
                }

                textColorHintObservable = when (colorHintAttr) {
                    COLOR_PRIMARY -> colors.textPrimary
                    COLOR_SECONDARY -> colors.textSecondary
                    COLOR_TERTIARY -> colors.textTertiary
                    else -> null
                }

                setTextSize(textSizeAttr)

                recycle()
            }
        }
    }

    /**
     * @see SIZE_PRIMARY
     * @see SIZE_SECONDARY
     * @see SIZE_TERTIARY
     * @see SIZE_TOOLBAR
     */
    fun setTextSize(textSizeAttr: Int) {
        val textSizePref = prefs.textSize.get()
        when (textSizeAttr) {
            SIZE_PRIMARY -> textSize = when (textSizePref) {
                Preferences.TEXT_SIZE_SMALL -> 14f
                Preferences.TEXT_SIZE_NORMAL -> 16f
                Preferences.TEXT_SIZE_LARGE -> 18f
                Preferences.TEXT_SIZE_LARGER -> 20f
                else -> 16f
            }

            SIZE_SECONDARY -> textSize = when (textSizePref) {
                Preferences.TEXT_SIZE_SMALL -> 12f
                Preferences.TEXT_SIZE_NORMAL -> 14f
                Preferences.TEXT_SIZE_LARGE -> 16f
                Preferences.TEXT_SIZE_LARGER -> 18f
                else -> 14f
            }

            SIZE_TERTIARY -> textSize = when (textSizePref) {
                Preferences.TEXT_SIZE_SMALL -> 10f
                Preferences.TEXT_SIZE_NORMAL -> 12f
                Preferences.TEXT_SIZE_LARGE -> 14f
                Preferences.TEXT_SIZE_LARGER -> 16f
                else -> 12f
            }

            SIZE_TOOLBAR -> textSize = when (textSizePref) {
                Preferences.TEXT_SIZE_SMALL -> 18f
                Preferences.TEXT_SIZE_NORMAL -> 20f
                Preferences.TEXT_SIZE_LARGE -> 22f
                Preferences.TEXT_SIZE_LARGER -> 26f
                else -> 20f
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isInEditMode) return

        fontProvider.typeface
                .autoDisposable(scope())
                .subscribe { setTypeface(it.value, typeface?.style ?: Typeface.NORMAL) }

        textColorObservable
                ?.autoDisposable(scope())
                ?.subscribe { color -> setTextColor(color) }

        textColorHintObservable
                ?.autoDisposable(scope())
                ?.subscribe { color -> setHintTextColor(color) }
    }

    override fun onCreateInputConnection(editorInfo: EditorInfo): InputConnection {

        val inputConnection = object : InputConnectionWrapper(super.onCreateInputConnection(editorInfo), true) {
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL) {
                    backspaces.onNext(Unit)
                }
                return super.sendKeyEvent(event)
            }


            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength == 1 && afterLength == 0) {
                    backspaces.onNext(Unit)
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
        }

        if (supportsInputContent) {
            EditorInfoCompat.setContentMimeTypes(editorInfo, arrayOf(
                    ContentType.IMAGE_JPEG,
                    ContentType.IMAGE_JPG,
                    ContentType.IMAGE_PNG,
                    ContentType.IMAGE_GIF))
        }

        val callback = InputConnectionCompat.OnCommitContentListener { inputContentInfo, flags, opts ->
            val grantReadPermission = flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 && grantReadPermission) {
                return@OnCommitContentListener tryOrNull {
                    inputContentInfo.requestPermission()
                    inputContentSelected.onNext(inputContentInfo)
                    true
                } ?: false

            }

            true
        }

        return InputConnectionCompat.createWrapper(inputConnection, editorInfo, callback)
    }

}