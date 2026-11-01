package com.rustvnt.remote

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Shared desktop design tokens for native cards, fields, buttons and status badges. */
class DesktopWidgets(val context: Context) {
    enum class ButtonKind { PRIMARY, SECONDARY, GHOST, DANGER }
    data class Field(val wrapper: TextInputLayout, val edit: TextInputEditText, val title: TextView) {
        fun visible(value: Boolean) { val visibility=if(value)View.VISIBLE else View.GONE;wrapper.visibility=visibility;title.visibility=visibility }
    }
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun color(resource: Int) = ContextCompat.getColor(context, resource)
    fun background(fill: Int, border: Int? = null, radius: Int = 9): GradientDrawable = GradientDrawable().apply {
        setColor(color(fill)); cornerRadius = dp(radius).toFloat()
        border?.let { setStroke(dp(1), color(it)) }
    }
    fun label(text: String, size: Float = 14f, tint: Int = R.color.vnt_text, bold: Boolean = false): TextView = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(color(tint))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    fun icon(resource: Int, tint: Int = R.color.vnt_text_2, size: Int = 20): ImageView = ImageView(context).apply {
        setImageResource(resource); imageTintList = ColorStateList.valueOf(color(tint))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun column(padding: Int = 0) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding)) }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun space(parent: LinearLayout, height: Int = 12) { parent.addView(Space(context), LinearLayout.LayoutParams(1, dp(height))) }
    fun divider(parent: LinearLayout) { parent.addView(View(context).apply { setBackgroundColor(color(R.color.vnt_border)) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin=dp(14);bottomMargin=dp(14) }) }
    fun button(text: String, kind: ButtonKind = ButtonKind.SECONDARY, icon: Int? = null, action: () -> Unit): MaterialButton = MaterialButton(context).apply {
        this.text = text; isAllCaps = false; textSize = 13f; cornerRadius = dp(9)
        minHeight = dp(48); minimumHeight = dp(48); insetTop=dp(2);insetBottom=dp(2)
        setPadding(dp(14),0,dp(14),0);elevation=0f;stateListAnimator=null
        val foreground = when(kind) { ButtonKind.PRIMARY -> android.R.color.white; ButtonKind.DANGER -> R.color.vnt_danger; else -> R.color.vnt_text_2 }
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),intArrayOf(color(R.color.vnt_text_3),color(foreground))))
        backgroundTintList=ColorStateList.valueOf(color(when(kind){ButtonKind.PRIMARY->R.color.vnt_brand;ButtonKind.DANGER->R.color.vnt_danger_tint;else->R.color.vnt_surface}))
        if(kind==ButtonKind.GHOST) backgroundTintList=ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        strokeWidth=if(kind==ButtonKind.SECONDARY)dp(1)else 0;strokeColor=ColorStateList.valueOf(color(R.color.vnt_border))
        rippleColor=ColorStateList.valueOf(color(R.color.vnt_brand_tint))
        icon?.let { setIconResource(it);iconGravity=MaterialButton.ICON_GRAVITY_TEXT_START;iconSize=dp(18);iconPadding=dp(7);iconTint=ColorStateList.valueOf(color(foreground)) }
        setOnClickListener { action() }
    }
    fun card(): LinearLayout = column(20).apply { background=background(R.color.vnt_surface,R.color.vnt_border,12) }
    fun panel(parent: LinearLayout, build: LinearLayout.() -> Unit): LinearLayout = card().apply {
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(16) });build()
    }
    fun heading(parent: LinearLayout, title: String, subtitle: String) {
        parent.addView(label(title,23f,bold=true));space(parent,5)
        parent.addView(label(subtitle,13f,R.color.vnt_text_2));space(parent,22)
    }
    fun cardHeading(parent: LinearLayout, title: String, subtitle: String? = null, glyph: Int = R.drawable.ic_connect) {
        val row=row();val tile=FrameLayout(context).apply { background=background(R.color.vnt_brand_tint,radius=9) }
        tile.addView(icon(glyph,R.color.vnt_brand),FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER))
        row.addView(tile,LinearLayout.LayoutParams(dp(36),dp(36)).apply {marginEnd=dp(12)})
        val words=column();words.addView(label(title,16f,bold=true))
        subtitle?.let {space(words,3);words.addView(label(it,12f,R.color.vnt_text_2))}
        row.addView(words,LinearLayout.LayoutParams(0,-2,1f));parent.addView(row);space(parent,18)
    }
    fun badge(text: String, success: Boolean = false): TextView = label(text,11f,if(success)R.color.vnt_ok else R.color.vnt_text_2).apply {
        background=background(if(success)R.color.vnt_ok_tint else R.color.vnt_surface_2,radius=6)
        setPadding(dp(8),dp(4),dp(8),dp(4))
    }
    fun field(parent: LinearLayout, title: String, value: String = "", type: Int = android.text.InputType.TYPE_CLASS_TEXT): Field {
        val caption=label(title,12f,R.color.vnt_text_2)
        parent.addView(caption,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12);bottomMargin=dp(5)})
        val wrapper=TextInputLayout(context).apply {
            isHintEnabled=false;boxBackgroundMode=TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxStrokeColorStateList(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf()),intArrayOf(color(R.color.vnt_brand),color(R.color.vnt_border_strong))))
            boxStrokeWidth=dp(1);boxStrokeWidthFocused=dp(2)
        }
        val edit=TextInputEditText(wrapper.context).apply {setText(value);hint=title;inputType=type;isSingleLine=true;textSize=14f;ellipsize=android.text.TextUtils.TruncateAt.END;setTextColor(color(R.color.vnt_text));setHintTextColor(color(R.color.vnt_text_3))}
        if (type and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) {
            edit.transformationMethod=android.text.method.PasswordTransformationMethod.getInstance()
        }
        wrapper.addView(edit);parent.addView(wrapper,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(4)})
        return Field(wrapper,edit,caption)
    }
    fun spinner(parent: LinearLayout, title: String, values: List<String>, selected: Int = 0): Spinner {
        parent.addView(label(title,12f,R.color.vnt_text_2));space(parent,7)
        return Spinner(context).apply {
            adapter=object:ArrayAdapter<String>(context,android.R.layout.simple_spinner_dropdown_item,values){
                override fun getView(position:Int,convertView:View?,parent:ViewGroup):View = (super.getView(position,convertView,parent) as TextView).apply {setTextColor(color(R.color.vnt_text));textSize=14f;setPadding(dp(12),dp(12),dp(12),dp(12))}
                override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup):View = (super.getDropDownView(position,convertView,parent) as TextView).apply {setTextColor(color(R.color.vnt_text));setBackgroundColor(color(R.color.vnt_surface));setPadding(dp(16),dp(14),dp(16),dp(14))}
            }
            setSelection(selected.coerceIn(values.indices));background=background(R.color.vnt_surface,R.color.vnt_border_strong)
            parent.addView(this,LinearLayout.LayoutParams(-1,-2))
        }
    }
    fun segments(parent: LinearLayout, choices: List<String>, selected: Int, changed: (Int)->Unit): LinearLayout {
        val rail=row().apply {setPadding(dp(4),dp(4),dp(4),dp(4));background=background(R.color.vnt_surface_2,radius=9)}
        choices.forEachIndexed { index,text ->
            val item=button(text,ButtonKind.GHOST) { changed(index) }.apply {
                isSelected=index==selected;setTextColor(color(if(isSelected)R.color.vnt_brand else R.color.vnt_text_2))
                backgroundTintList=ColorStateList.valueOf(color(if(isSelected)R.color.vnt_surface else R.color.vnt_surface_2))
                minWidth=0;minimumWidth=0;minHeight=dp(40);insetTop=0;insetBottom=0
                setPadding(dp(5),0,dp(5),0)
            }
            rail.addView(item,LinearLayout.LayoutParams(0,dp(40),1f))
        }
        parent.addView(rail,LinearLayout.LayoutParams(-1,-2));return rail
    }
}
