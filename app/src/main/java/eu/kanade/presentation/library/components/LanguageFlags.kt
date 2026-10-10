package eu.kanade.presentation.library.components

import eu.kanade.tachiyomi.R

/**
 * Maps a source language code to a flag drawable, null when there is no matching flag.
 *
 * Ported from Komikku.
 */
fun getLanguageIconID(lang: String): Int? {
    return when (lang) {
        "all" -> R.drawable.flag_un
        // "other" -> R.drawable.flag_globe
        "af" -> R.drawable.flag_za // Afrikaans -> South Africa, ZA
        "am" -> R.drawable.flag_et // Amharic -> Ethiopia, ET
        "ar" -> R.drawable.flag_eg // Arabic -> Egypt, EG - Saudi Arabia, SA
        "az" -> R.drawable.flag_az // Azerbaijani -> Azerbaijan, AZ
        "be" -> R.drawable.flag_by // Belarusian -> Belarus, BY
        "bg" -> R.drawable.flag_bg // Bulgarian -> Bulgaria, BG
        "bn" -> R.drawable.flag_bd // Bengali -> Bangladesh, BD
        "br" -> R.drawable.flag_fr // Breton -> France, FR
        "bs" -> R.drawable.flag_ba // Bosnia and Herzegovina, BA
        "ca" -> R.drawable.flag_es // Catalan -> Spain, ES
        "ceb" -> R.drawable.flag_ph // Cebuano -> Philippines, PH
        "cn" -> R.drawable.flag_cn // Chinese -> China, CN
        "co" -> R.drawable.flag_es // Corsican -> Spain, ES
        "cs" -> R.drawable.flag_cz // Czech -> Czech Republic, CZ
        "da" -> R.drawable.flag_dk // Danish -> Denmark, DK
        "de" -> R.drawable.flag_de // German -> Germany, DE
        "el" -> R.drawable.flag_gr // Greek -> Greece, GR
        "en" -> R.drawable.flag_us // English -> United States, US
        "eo" -> R.drawable.flag_esperanto // Esperanto -> no country
        "es-419" -> R.drawable.flag_mx // Spanish -> Mexico MX, Latin America, Latin America
        "es" -> R.drawable.flag_es // Spanish -> Spain, ES
        "et" -> R.drawable.flag_ee // Estonian -> Estonia, EE
        "eu" -> R.drawable.flag_es // Basque -> Spain, ES
        "fa" -> R.drawable.flag_ir // Persian -> Iran, IR
        "fi" -> R.drawable.flag_fi // Finnish -> Finland, FI
        "fil" -> R.drawable.flag_ph // Filipino -> Philippines, PH
        "fo" -> R.drawable.flag_fo // Faroese -> Faroe Island, FO
        "fr" -> R.drawable.flag_fr // French -> France, FR
        "ga" -> R.drawable.flag_ie // Irish -> Ireland, IE
        "gn" -> R.drawable.flag_py // Guarani -> Paraguay, PY
        "gu" -> R.drawable.flag_in // Gujarati -> India, IN
        "ha" -> R.drawable.flag_ng // Hausa -> Nigeria, NG
        "he" -> R.drawable.flag_il // Hebrew -> Israel, IL
        "hi" -> R.drawable.flag_in // Hindi -> India, IN
        "hr" -> R.drawable.flag_hr // Croatian -> Croatia, HR
        "ht" -> R.drawable.flag_ht // Haitian -> Haiti, HT
        "hu" -> R.drawable.flag_hu // Hungarian -> Hungary, HU
        "hy" -> R.drawable.flag_am // Armenian -> Armenia, AM
        "id" -> R.drawable.flag_id // Indonesian -> Indonesia, ID
        "ig" -> R.drawable.flag_ng // Igbo -> Nigeria, NG
        "is" -> R.drawable.flag_is_ // Icelandic -> Iceland, IS
        "it" -> R.drawable.flag_it // Italian -> Italy, IT
        "ja" -> R.drawable.flag_jp // Japanese -> Japan, JP
        "jv" -> R.drawable.flag_id // Javanese -> Indonesia, ID
        "ka" -> R.drawable.flag_ge // Georgian -> Georgia, GE
        "kk" -> R.drawable.flag_kz // Kazakh -> Kazakhstan, KZ
        "km" -> R.drawable.flag_kh // Khmer -> Cambodia, KH
        "kn" -> R.drawable.flag_in // Kannada -> India, IN
        "ko" -> R.drawable.flag_kr // Korean -> South Korea, KR
        "kr" -> R.drawable.flag_ng // Kanuri -> Nigeria, NG
        "ku" -> R.drawable.flag_iq // Kurdish -> Iraq, IQ
        "ky" -> R.drawable.flag_kg // Kyrgyz -> Kyrgyzstan, KG
        // "la" -> R.drawable.flag_la       // Latin -> Latin America, Latin America
        "lb" -> R.drawable.flag_lu // Luxembourgish -> Luxembourg, LU
        "lmo" -> R.drawable.flag_it // Lombard, Italy, IT
        "lo" -> R.drawable.flag_la // Lao -> Laos, LA
        "lt" -> R.drawable.flag_lt // Lithuanian -> Lithuania, LT
        "lv" -> R.drawable.flag_lv // Latvian -> Latvia, LV
        "mg" -> R.drawable.flag_mg // Malagasy -> Madagascar, MG
        "mi" -> R.drawable.flag_nz // Maori -> New Zealand, NZ
        "mk" -> R.drawable.flag_mk // Macedonian -> Macedonia, MK
        "ml" -> R.drawable.flag_in // Malayalam -> India, IN
        "mn" -> R.drawable.flag_mn // Mongolian -> Mongolia, MN
        "mo" -> R.drawable.flag_md // Moldavian -> Moldova, MD
        "mr" -> R.drawable.flag_in // Marathi -> India, IN
        "ms" -> R.drawable.flag_my // Malay -> Malaysia, MY
        "mt" -> R.drawable.flag_mt // Maltese -> Malta, MT
        "my" -> R.drawable.flag_mm // Myanmar -> Myanmar, MM
        "ne" -> R.drawable.flag_np // Nepali -> Nepal, NP
        "nl" -> R.drawable.flag_nl // Dutch -> Netherlands, NL
        "no" -> R.drawable.flag_no // Norwegian -> Norway, NO
        "ny" -> R.drawable.flag_mw // Nyanja -> Malawi, MW
        "pl" -> R.drawable.flag_pl // Polish -> Poland, PL
        "ps" -> R.drawable.flag_af // Pashto -> Afghanistan, AF - Pakistan, PK
        "pt-BR" -> R.drawable.flag_br // Portuguese, Brazil, BR
        "pt-PT" -> R.drawable.flag_pt // Portuguese, Portugal, PT
        "pt" -> R.drawable.flag_pt // Portuguese -> Portugal, PT
        "rm" -> R.drawable.flag_ch // Romansh -> Switzerland, SW
        "ro" -> R.drawable.flag_ro // Romanian -> Romania, RO
        "ru" -> R.drawable.flag_ru // Russian -> Russia, RU
        "sd" -> R.drawable.flag_pk // Sindhi -> Pakistan, PK
        "sh" -> R.drawable.flag_hr // Serbo-Croatian -> Serbia, HR
        "si" -> R.drawable.flag_lk // Sinhalese -> Sri Lanka, LK
        "sk" -> R.drawable.flag_sk // Slovak -> Slovakia, SK
        "sl" -> R.drawable.flag_si // Slovenian -> Slovenia, SI
        "sm" -> R.drawable.flag_ws // Samoan -> Samoa, WS
        "sn" -> R.drawable.flag_zw // Shona -> Zimbabwe, ZW
        "so" -> R.drawable.flag_so // Somali -> Somalia, SO
        "sq" -> R.drawable.flag_al // Albanian -> Albania, AL
        "sr" -> R.drawable.flag_hr // Serbian -> Serbia, HR
        "st" -> R.drawable.flag_ls // Sesotho -> South Africa, ZA - Lesotho, LS
        "sv" -> R.drawable.flag_se // Swedish -> Sweden, SE
        "sw" -> R.drawable.flag_tz // Swahili -> Tanzania, TZ - Kenya, KE
        "ta" -> R.drawable.flag_in // Tamil -> India, IN
        "te" -> R.drawable.flag_in // Telugu -> India, IN
        "tg" -> R.drawable.flag_tj // Tajik -> Tajikistan, TJ
        "th" -> R.drawable.flag_th // Thai -> Thailand, TH
        "ti" -> R.drawable.flag_er // Tigrinya -> Eritrea, ER
        "tk" -> R.drawable.flag_tm // Turkmen -> Turkmenistan, TM
        "tl" -> R.drawable.flag_ph // Filipino -> Philippines, PH
        "to" -> R.drawable.flag_to // Tonga -> Tonga, TO
        "tr" -> R.drawable.flag_tr // Turkish -> Turkey, TR
        "uk" -> R.drawable.flag_ua // Ukrainian -> Ukraine, UA
        "ur" -> R.drawable.flag_pk // Urdu -> Pakistan, PK
        "uz" -> R.drawable.flag_uz // Uzbek -> Uzbekistan, UZ
        "vec" -> R.drawable.flag_it // Venetian -> Italy, IT
        "vi" -> R.drawable.flag_vn // Vietnamese -> Vietnam, VN
        "yo" -> R.drawable.flag_ng // Yoruba -> Nigeria, NG
        "zh-Hans" -> R.drawable.flag_cn // Chinese, simplified -> China, CN
        "zh-Hant" -> R.drawable.flag_tw // Chinese, traditional -> Taiwan, TW
        "zh" -> R.drawable.flag_cn // Chinese -> China, CN
        "zu" -> R.drawable.flag_za // Zulu -> South Africa, ZA
        // Additional from App language
        "gl" -> R.drawable.flag_es // Galician -> Spain, ES
        "in" -> R.drawable.flag_id // Indonesian -> Indonesia, ID
        "nb-NO" -> R.drawable.flag_no // Norwegian Bokmål -> Norway, NO
        "nn" -> R.drawable.flag_no // Norwegian Nynorsk -> Norway, NO
        "sc" -> R.drawable.flag_it // Sardinian -> Italy, IT
        "sdh" -> R.drawable.flag_ir // Southern Kurdish -> Iran, IR
        "sah" -> R.drawable.flag_ru // Sakha -> Russia, RU
        "cv" -> R.drawable.flag_ru // Chuvash -> Russia, RU
        "sa" -> R.drawable.flag_in // Sanskrit -> India, IN
        "ka-GE" -> R.drawable.flag_ge // Georgian -> Georgia, GE
        "zh-CN" -> R.drawable.flag_cn // Chinese, simplified -> China, CN
        "zh-TW" -> R.drawable.flag_tw // Chinese, traditional -> Taiwan, TW"
        else -> null
    }
}
