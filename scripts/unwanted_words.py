#!/usr/bin/env python3
"""Makes server/src/main/resources/unwanted-words.txt: the words no user name and no session ID may contain.

    python3 scripts/unwanted_words.py            # downloads the lists and writes the file
    python3 scripts/unwanted_words.py <folder>   # from lists downloaded before (one file per language code)

From the "List of Dirty, Naughty, Obscene, and Otherwise Bad Words" (LDNOOBW, CC BY 4.0,
https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words), the languages in LANGUAGES, made
fit for names: lower case, without accents (umlauts also as ae/oe/ue), phrases written together, only a-z and 0-9,
at least 3 letters. OURS adds words; SHORT_KEPT and ALLOWED take out what is harmless in a name or an ordinary word
somewhere. In the file a word stands plain (it matches a whole part of a name, and inside a longer word if it has 7
letters or more), with * (inside a longer word whatever its length) or with = (only as a whole part). How they are
matched: UnwantedWords.kt.
"""
import pathlib
import sys
import unicodedata
import urllib.request

SOURCE = "https://raw.githubusercontent.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words/master/"
LANGUAGES = ["en", "de", "fr", "es", "it", "nl", "pl", "pt", "tr", "sv", "da", "no", "fi", "cs", "hu"]
TARGET = pathlib.Path(__file__).resolve().parent.parent / "server/src/main/resources/unwanted-words.txt"

# Not in the lists, or only in another form. A leading * matches inside a longer word whatever its length.
OURS = [
    "*fuck", "*bitch", "*nigger", "*nigga", "*whore", "*pussy", "*penis", "*faggot", "*hitler", "*fotze", "*wichse",
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

# Harmless in a name, or an ordinary word or name in English or German (checked against the 10,000 commonest
# English words, the 15,000 commonest German ones and 4,900 first names)
ALLOWED = {
    "anita", "bastard", "bitte", "bondage", "boobs", "busty", "cialis", "dritt", "erotic", "escort", "franc",
    "gotten", "hardcore", "hentai", "horny", "lolita", "nackt", "nicht", "nippel", "nipple", "nipples", "nudity",
    "orgasm", "orgasmus", "panties", "peter", "pinkeln", "pippa", "playboy", "regina", "rosette", "satan", "sexual",
    "sexuality", "sexually", "stake", "sucks", "threesome", "thumbzilla", "topless", "travesti", "viagra", "vibrator",
    "voyeur", "voyeurweb", "voyuer", "amador", "gerber", "inferno", "cumming", "homosexual", "poppen", "doedel",
    "luemmel", "fratze",
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


def main() -> int:
    words = set()
    for language in LANGUAGES:
        if len(sys.argv) > 1:
            text = (pathlib.Path(sys.argv[1]) / f"{language}.txt").read_text(encoding="utf-8")
        else:
            with urllib.request.urlopen(SOURCE + language, timeout=30) as answer:
                text = answer.read().decode("utf-8")
        for line in text.splitlines():
            words.update(fit(line))
    words = {w for w in words if (len(w) >= 5 or w in SHORT_KEPT) and w not in ALLOWED}
    inside = {w[1:] for w in OURS if w.startswith("*")}
    words |= {w.lstrip("*") for w in OURS}
    lines = sorted(("*" if w in inside else "=" if w in WHOLE_ONLY else "") + w for w in words)
    header = [
        "# The words no user name and no session ID may contain; how they are matched: UnwantedWords.kt.",
        "# Made by scripts/unwanted_words.py: don't edit by hand, change its OURS and ALLOWED and run it again.",
        "# From the List of Dirty, Naughty, Obscene, and Otherwise Bad Words (LDNOOBW), CC BY 4.0:",
        "# https://github.com/LDNOOBW/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words",
        "# (" + ", ".join(LANGUAGES) + "), changed: fit for names, some taken out, some added.",
    ]
    TARGET.write_text("\n".join(header + lines) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(lines)} words -> {TARGET}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
