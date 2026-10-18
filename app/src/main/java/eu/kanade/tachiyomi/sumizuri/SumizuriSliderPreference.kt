package eu.kanade.tachiyomi.sumizuri

import android.content.Context
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.slider.RangeSlider
import eu.kanade.tachiyomi.R
import kotlin.math.roundToInt

/**
 * KMK: a Sumizuri source setting of type "slider": one handle, or two for a range ("1-5"), like
 * Settings › Library › Pause between titles. The value is saved as text when a handle is let go.
 */
class SumizuriSliderPreference(
    context: Context,
    private val slider: SumizuriPreferences.Slider,
) : Preference(context) {

    private var value: String? = null

    init {
        layoutResource = R.layout.pref_sumizuri_slider
        isSelectable = false
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        value = getPersistedString(defaultValue as? String ?: "${slider.min}")
    }

    override fun onGetDefaultValue(a: android.content.res.TypedArray, index: Int): Any? = a.getString(index)

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false
        val bar = holder.findViewById(R.id.sumizuri_slider) as RangeSlider
        val label = holder.findViewById(R.id.sumizuri_slider_value) as TextView
        val (from, to) = SumizuriPreferences.sliderValues(value, slider)

        bar.clearOnChangeListeners()
        bar.clearOnSliderTouchListeners()
        bar.valueFrom = slider.min.toFloat()
        bar.valueTo = slider.max.toFloat()
        bar.stepSize = slider.step.toFloat()
        bar.values = if (slider.range) listOf(from.toFloat(), to.toFloat()) else listOf(from.toFloat())
        if (slider.range) bar.minSeparation = 0f
        label.text = SumizuriPreferences.sliderLabel(from, to, slider)

        bar.addOnChangeListener { view, _, _ ->
            val (a, b) = view.handles()
            label.text = SumizuriPreferences.sliderLabel(a, b, slider)
        }
        bar.addOnSliderTouchListener(
            object : RangeSlider.OnSliderTouchListener {
                override fun onStartTrackingTouch(view: RangeSlider) = Unit

                override fun onStopTrackingTouch(view: RangeSlider) {
                    val (a, b) = view.handles()
                    val text = SumizuriPreferences.sliderText(a, b, slider)
                    if (text != value && callChangeListener(text)) {
                        value = text
                        persistString(text)
                    }
                }
            },
        )
    }

    private fun RangeSlider.handles(): Pair<Int, Int> {
        val list = values.map { it.roundToInt() }
        val a = list.firstOrNull() ?: slider.min
        return a to (list.getOrNull(1) ?: a).coerceAtLeast(a)
    }
}
