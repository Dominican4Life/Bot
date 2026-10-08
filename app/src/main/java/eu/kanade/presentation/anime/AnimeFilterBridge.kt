package eu.kanade.presentation.anime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/*
 * KMK: Anime sources describe their filters with the same shapes as manga sources. These copy anime filters into
 * manga filters so the manga filter sheet can show them, then copy the user's choices back.
 */

private interface Mirror {
    fun writeBack()
}

private class MirrorHeader(name: String) : Filter.Header(name), Mirror {
    override fun writeBack() {}
}

private class MirrorSeparator(name: String) : Filter.Separator(name), Mirror {
    override fun writeBack() {}
}

private class MirrorSelect(val src: AnimeFilter.Select<*>) :
    Filter.Select<Any?>(src.name, src.values.map { it as Any? }.toTypedArray(), src.state),
    Mirror {
    override fun writeBack() {
        src.state = state
    }
}

private class MirrorText(val src: AnimeFilter.Text) : Filter.Text(src.name, src.state), Mirror {
    override fun writeBack() {
        src.state = state
    }
}

private class MirrorCheckBox(val src: AnimeFilter.CheckBox) : Filter.CheckBox(src.name, src.state), Mirror {
    override fun writeBack() {
        src.state = state
    }
}

private class MirrorTriState(val src: AnimeFilter.TriState) : Filter.TriState(src.name, src.state), Mirror {
    override fun writeBack() {
        src.state = state
    }
}

private class MirrorSort(val src: AnimeFilter.Sort) :
    Filter.Sort(src.name, src.values, src.state?.let { Selection(it.index, it.ascending) }),
    Mirror {
    override fun writeBack() {
        src.state = state?.let { AnimeFilter.Sort.Selection(it.index, it.ascending) }
    }
}

private class MirrorAutoComplete(val src: AnimeFilter.AutoComplete) :
    Filter.AutoComplete(
        name = src.name,
        hint = src.hint,
        values = src.values,
        skipAutoFillTags = src.skipAutoFillTags,
        validPrefixes = src.validPrefixes,
        state = src.state,
    ),
    Mirror {
    override fun writeBack() {
        src.state = state
    }
}

private class MirrorGroup(val src: AnimeFilter.Group<*>, children: List<Filter<*>>) :
    Filter.Group<Filter<*>>(src.name, children),
    Mirror {
    override fun writeBack() {
        state.forEach { (it as? Mirror)?.writeBack() }
    }
}

private fun AnimeFilter<*>.toMirror(): Filter<*>? = when (this) {
    is AnimeFilter.Header -> MirrorHeader(name)
    is AnimeFilter.Separator -> MirrorSeparator(name)
    is AnimeFilter.Select<*> -> MirrorSelect(this)
    is AnimeFilter.Text -> MirrorText(this)
    is AnimeFilter.CheckBox -> MirrorCheckBox(this)
    is AnimeFilter.TriState -> MirrorTriState(this)
    is AnimeFilter.Sort -> MirrorSort(this)
    is AnimeFilter.AutoComplete -> MirrorAutoComplete(this)
    is AnimeFilter.Group<*> -> MirrorGroup(
        this,
        state.mapNotNull { (it as? AnimeFilter<*>)?.toMirror() },
    )
}

/** A manga-style copy of these filters for the filter sheet */
fun AnimeFilterList.toMangaFilterList(): FilterList = FilterList(list.mapNotNull { it.toMirror() })

/** Copies the choices made in the filter sheet back into the anime filters */
fun FilterList.writeBackToAnime() {
    forEach { (it as? Mirror)?.writeBack() }
}
