package eu.kanade.tachiyomi.extension.id.hentaicrot

import eu.kanade.tachiyomi.multisrc.oceanwp.OceanWP
import eu.kanade.tachiyomi.source.model.FilterList

class HentaiCrot : OceanWP("Hentai Crot", "https://hentaicrot.com", "id") {

    override fun getFilterList(): FilterList = FilterList(
        CategoryFilter(),
    )

    private class CategoryFilter : UriPartFilter(
        "Category",
        arrayOf(
            Pair("All", ""),
            Pair("Anal", "anal"),
            Pair("Berwarna", "berwarna"),
            Pair("Blowjob", "blowjob"),
            Pair("Doggy Style", "doggy-style"),
            Pair("Double Penetration", "double-penetration"),
            Pair("Entot Mama", "entot-mama"),
            Pair("Full Color", "full-color"),
            Pair("Milftoon", "milftoon"),
            Pair("Pelajar", "pelajar"),
            Pair("Perkosa", "perkosa"),
            Pair("Sedarah", "sedarah"),
            Pair("Toket Besar", "toket-besar"),
            Pair("Tante Semok", "tante-semok"),
            Pair("Uncensored", "uncensored"),
            Pair("3D", "3d"),
        ),
    )
}
