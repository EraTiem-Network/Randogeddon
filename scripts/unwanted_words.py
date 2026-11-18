#!/usr/bin/env python3
"""Makes server/src/main/resources/unwanted-words.txt: the words no user name and no session ID may contain.

    python3 scripts/unwanted_words.py            # downloads what it needs into output/unwanted-words/, then writes
    python3 scripts/unwanted_words.py <folder>   # the same, with <folder> as the download folder (kept between runs)

The words come from three lists, in the languages written in Latin letters (a name here has only A-Z, a-z, 0-9, _):
- the "List of Dirty, Naughty, Obscene, and Otherwise Bad Words" (LDNOOBW), CC BY 4.0,
  https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words
- BadWords by FlacSy and contributors, MIT, https://github.com/FlacSy/badwords (it includes LDNOOBW and
  washyourmouthoutwithsoap, MIT, https://github.com/thisandagain/washyourmouthoutwithsoap)
- Google Profanity Words by Coffee & Fun, MIT, https://github.com/coffee-and-fun/google-profanity-words
They are made fit for names: lower case, without accents (umlauts also as ae/oe/ue), phrases written together, only
a-z and 0-9, at least 3 letters.

Ordinary words are taken out by checking against the 50,000 commonest words of each language (FrequencyWords by
Hermit Dave, https://github.com/hermitdave/FrequencyWords, from subtitles) and 4,900 first names
(https://github.com/dominictarr/random-name); those lists are only used here, nothing of them goes into the file:
- a word only BadWords has goes if it is common in any language (one of its own language's 30,000 commonest, or one
  of another's 20,000): BadWords has many ordinary words;
- the other lists' words stay (a swear word is common in other languages' subtitles too); the ordinary ones of
  them, found that way, are in ALLOWED; a first name goes;
- a word with 7 letters or more found inside a common word or a name (Therapist, Marsch) only counts as a whole part.
Then OURS adds words; SHORT_KEPT and ALLOWED take out more (shorter than 5 letters only what SHORT_KEPT has).

In the file a word stands plain (it matches a whole part of a name, and inside a longer word if it has 7 letters or
more), with * (inside a longer word whatever its length) or with = (only as a whole part). How they are matched:
UnwantedWords.kt.
"""
import json
import pathlib
import sys
import unicodedata
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
TARGET = ROOT / "server/src/main/resources/unwanted-words.txt"
LANGUAGES = ["cs", "da", "de", "en", "es", "fi", "fr", "hu", "id", "it", "nl", "no", "pl", "pt", "ro", "sv", "tr"]

LDNOOBW = "https://raw.githubusercontent.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words/master/{}"
BADWORDS = "https://raw.githubusercontent.com/FlacSy/badwords/master/js/languages/lang/{}.json"
# BadWords' regional lists, read with their language
BADWORDS_EXTRA = {"es": ["es_419"], "pt": ["pt_br"]}
# The languages LDNOOBW has (Indonesian and Romanian only BadWords)
LDNOOBW_LANGUAGES = [l for l in LANGUAGES if l not in ("id", "ro")]
GOOGLE = "https://raw.githubusercontent.com/coffee-and-fun/google-profanity-words/main/data/{}.txt"
GOOGLE_LANGUAGES = ["en", "es", "fr"]
FREQUENCY = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/{0}/{0}_50k.txt"
NAMES = "https://raw.githubusercontent.com/dominictarr/random-name/master/first-names.txt"

# Common in another language: one of its this many commonest words
COMMON = 20_000
# A word only BadWords has, common in its own language: one of its this many
COMMON_OWN = 30_000
# Found inside a word that common, or a name: only as a whole part
INSIDE_FROM = 7

# Not in the lists, or only in another form. A leading * matches inside a longer word whatever its length.
OURS = [
    "*fuck", "*bitch", "*nigger", "*nigga", "*whore", "*pussy", "penis", "*faggot", "*hitler", "*fotze", "*wichse",
    "*arschloch", "*hurensohn", "*schlampe", "*holocaust", "*pedophil", "*paedophil", "*siegheil", "*blowjob",
    "*handjob", "*cumshot", "*gangbang", "kkk", "heil", "nazis", "jihad", "pedo", "paedo", "rapist", "wichser",
    "missgeburt", "spast", "spasti", "kanake", "untermensch", "vergasen", "retard", "asshole", "arsehole", "wanker",
]

# Shorter than 5 letters, the lists' words are ordinary ones in some language (fan, got, hard, mama, trio): only
# these stay
SHORT_KEPT = {
    "ass", "fag", "cum", "xxx", "anal", "anus", "clit", "cock", "cunt", "dick", "fick", "fuck", "hure", "jizz",
    "kike", "milf", "nazi", "paki", "pedo", "porn", "rape", "shit", "slut", "spic", "tits", "twat", "wank", "puta",
    "pute", "chuj", "huj", "kut",
}

# Harmless in a name, or an ordinary word the checks above don't catch
ALLOWED = {
    "anita", "bastard", "bitte", "bondage", "boobs", "busty", "cialis", "dritt", "erotic", "escort", "franc",
    "gotten", "hardcore", "hentai", "horny", "lolita", "nackt", "nicht", "nippel", "nipple", "nipples", "nudity",
    "orgasm", "orgasmus", "panties", "peter", "pinkeln", "pippa", "playboy", "regina", "rosette", "satan", "sexual",
    "sexuality", "sexually", "stake", "sucks", "threesome", "thumbzilla", "topless", "travesti", "viagra", "vibrator",
    "voyeur", "voyeurweb", "voyuer", "amador", "gerber", "inferno", "cumming", "homosexual", "poppen", "doedel",
    "luemmel", "fratze", "procionelavatore", "espacer", "mesange", "dikmek", "takkenbossen", "achterschip", "flens",
    "mikpunt",
    # Ordinary, or harmless in a name, in their own language (checked against each language's 10,000 commonest)
    "negro", "negros", "direccion", "labios", "asesinato", "asesinatos", "infierno", "hamburguesa", "hamburguesas",
    "martillo", "persiguiendo", "cocinando", "basurero", "francot", "francotirador", "esclavitud", "frijoles",
    "chiflado", "payaso", "borrachos", "borracho", "senos", "desnudo", "desnudos", "tonterias", "estupideces", "aborto",
    "chulo", "cadavere", "cadaveres", "pelotas", "maldito", "malditos", "maldicion", "tontas", "pesce", "battere",
    "montare", "dolcetto", "dolcett", "finocchio", "sbattere", "vacca", "femminuccia", "baiser", "bourre", "bourree",
    "jouir", "chiant", "deconne", "deconner", "deconnes", "pedale", "gueule", "schatje", "naakt", "watje", "naaien",
    "zuigen", "griet", "spuiten", "flikken", "pijpen", "rukken", "engerd", "belazeren", "klojo", "cerveja", "cervejas",
    "aranha", "consolo", "veado", "duche", "bestial", "gozar", "jajko", "hitto", "jaetkae", "jaetkaet", "naida",
    "pistaeae", "reikae", "pallit", "lutka", "marha", "barom", "tusan", "forbanna", "forbannet", "helvete",
    # The same, among the 10,000th to 30,000th commonest
    "pollock", "lovell", "twinkie", "exclama", "megragad", "huevely", "marhat", "marhak", "barmok", "barmot", "grieta",
    "lujuria", "sangriento", "sangrientos", "herrero", "mapache", "mapaches", "bromista", "comadreja", "coquetear",
    "dique", "quejandose", "fascina", "culata", "rizado", "dominacion", "asaltante", "asaltantes", "picazon", "frijol",
    "lanzallamas", "pezon", "eunuco", "conos", "sufferd", "aftrekken", "wippen", "dombo", "palen", "toeter",
    "standje", "zaadje", "bedonderen", "loden", "matje", "remsporen", "potverdorie", "opzouten", "klooien",
    "mafketel", "zuiplap", "dmuchac", "rzygac", "trique", "gerbe", "rectum", "tavara", "kusta", "pantava", "madon",
    "helvetisti", "spagnola", "mannaggia", "quaglia", "pirla", "pomiciare", "torneira", "cibernetico", "bissexual",
    "sinnsykt", "potta", "sikten",
    # The lists' own words that are ordinary in another language (among its 20,000 commonest)
    "apara", "apelo", "asbak", "aspirar", "balen", "bander", "bandido", "bekken", "bofetada", "brinca", "brutta",
    "buscar", "buzna", "cabra", "campana", "carino", "cocaina", "comer", "concha", "corno", "corrida", "debil",
    "drogas", "ducha", "erotico", "escolta", "esperma", "estupido", "fanken", "fanny", "farty", "foder", "folla",
    "folle", "gallo", "gecit", "golpe", "gordo", "gringo", "guido", "heroina", "heterosexual", "idiota", "imbecil",
    "imbeciles", "japon", "jatka", "jockey", "johny", "kanen", "kummer", "lesbiana", "lesbica", "mamar", "mando",
    "minge", "moras", "mordida", "moreno", "mutta", "negre", "orgia", "palle", "panna", "perilla", "perse",
    "pervertido", "pinto", "pises", "pisin", "pokker", "pompa", "popel", "porca", "porco", "prostituta", "punta",
    "pusse", "racista", "sikte", "sikler", "tette", "tirare", "tonto", "willy", "domination", "douche", "helvetet",
    "kinky", "bloody", "bimbo", "chupar", "burdel", "gspot", "lesbo", "pussi", "anjingk",
    # Inside ordinary words or names
    "ische", "conne", "verga", "cesso", "etron", "gotte", "orina", "chier", "semen", "sulin", "goette", "beurt",
    "balle", "monta", "chatte", "hoerig", "bordel", "ryssa", "panty", "fitte", "swinger", "burro", "twink", "eikel",
    "schiesser",
}

# Only as a whole word (a name's part), never inside a longer one: Marsch, Therapist
WHOLE_ONLY = {"arsch", "rapist"}


def fit(word: str) -> list:
    """[word] as it could stand in a name: lower case, no accents; German umlauts also written out."""
    w = word.strip().lower()
    forms = {w}
    if any(c in w for c in "äöüß"):
        forms.add(w.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss"))
    out = []
    for form in forms:
        plain = "".join(c for c in unicodedata.normalize("NFKD", form) if not unicodedata.combining(c))
        plain = "".join(c for c in plain if c.isascii() and c.isalnum())
        if len(plain) >= 3 and any(c.isalpha() for c in plain):
            out.append(plain)
    return out


def fetch(folder: pathlib.Path, name: str, url: str) -> str:
    """[url]'s text, downloaded into [folder] as [name] once."""
    file = folder / name
    if not file.exists():
        file.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(url, timeout=60) as answer:
            file.write_bytes(answer.read())
    return file.read_text(encoding="utf-8", errors="ignore")


def main() -> int:
    folder = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "output/unwanted-words"
    found = {}  # word -> its languages
    curated = set()  # words LDNOOBW or the Google list have
    bad_words = set()

    def add(text_words, language, source):
        for line in text_words:
            for w in fit(line):
                found.setdefault(w, set()).add(language)
                (bad_words if source == "badwords" else curated).add(w)

    for language in LANGUAGES:
        if language in LDNOOBW_LANGUAGES:
            add(fetch(folder, f"ldnoobw/{language}.txt", LDNOOBW.format(language)).splitlines(), language, "ldnoobw")
        for code in [language] + BADWORDS_EXTRA.get(language, []):
            add(json.loads(fetch(folder, f"badwords/{code}.json", BADWORDS.format(code))), language, "badwords")
    for language in GOOGLE_LANGUAGES:
        add(fetch(folder, f"google/{language}.txt", GOOGLE.format(language)).splitlines(), language, "google")

    ranks = {}
    for language in LANGUAGES:
        rank = {}
        for n, line in enumerate(fetch(folder, f"frequency/{language}.txt", FREQUENCY.format(language)).splitlines()):
            if line.split():
                for w in fit(line.split()[0]):
                    rank.setdefault(w, n)
        ranks[language] = rank
    names = {w for line in fetch(folder, "names.txt", NAMES).splitlines() for w in fit(line)}

    def common(w, languages, top):
        return any(ranks[language].get(w, top) < top for language in languages)

    words = set()
    for w, languages in found.items():
        if len(w) < 5 and w not in SHORT_KEPT:
            continue
        if w in ALLOWED or (w in names and w not in SHORT_KEPT):
            continue
        # BadWords alone: not if it is common anywhere. The others' words stay: a swear word is common in the
        # subtitles of other languages too (fuck, shit); what is ordinary there is in ALLOWED
        if w not in curated and (common(w, languages, COMMON_OWN) or common(w, LANGUAGES, COMMON)):
            continue
        words.add(w)
    inside = {w[1:] for w in OURS if w.startswith("*")}
    words |= {w.lstrip("*") for w in OURS}

    # Inside a common word or a name that isn't itself one of them, nor a form of it: only as a whole part
    ordinary = {w for rank in ranks.values() for w, n in rank.items() if n < COMMON_OWN and w not in words} | names
    whole = set(WHOLE_ONLY)
    for o in ordinary:
        # Every part of it from the second letter on (a part at its start is a form of the word itself)
        for start in range(1, len(o) - INSIDE_FROM + 1):
            for end in range(start + INSIDE_FROM, len(o) + 1):
                part = o[start:end]
                if part in words and part not in inside:
                    whole.add(part)

    lines = sorted(("*" if w in inside else "=" if w in whole else "") + w for w in words)
    header = [
        "# The words no user name and no session ID may contain; how they are matched: UnwantedWords.kt.",
        "# Made by scripts/unwanted_words.py: don't edit by hand, change its OURS and ALLOWED and run it again.",
        "# From, changed (fit for names, ordinary words taken out, some added), in " + ", ".join(LANGUAGES) + ":",
        "# - List of Dirty, Naughty, Obscene, and Otherwise Bad Words (LDNOOBW), CC BY 4.0:",
        "#   https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words",
        "# - BadWords by FlacSy and contributors, MIT: https://github.com/FlacSy/badwords",
        "# - Google Profanity Words by Coffee & Fun, MIT: https://github.com/coffee-and-fun/google-profanity-words",
    ]
    TARGET.write_text("\n".join(header + lines) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(lines)} words ({len(whole)} only as a whole part) -> {TARGET}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
