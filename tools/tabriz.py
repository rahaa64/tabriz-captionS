# Reference converter: whisper Latin Azeri (Baku/Turkey spelling) -> Persian-script Tabriz captions.
# The Android app (TabrizConverter.kt) implements exactly the same algorithm on the same tabriz_rules.json;
# tests/vectors.json produced here are checked by the Kotlin unit tests in CI.
import json, re, os, sys
R = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'tabriz_rules.json'), encoding='utf-8'))
VOW = set('aəeıioöuü')
ZWNJ = '‌'
FA_DIG = str.maketrans('0123456789', '۰۱۲۳۴۵۶۷۸۹')
LOWER = {'I': 'ı', 'İ': 'i'}

def lower(w):
    return ''.join(LOWER.get(c, c) for c in w).lower()

def normalize(word):
    """Latin word -> Tabriz Latin (word map + suffix rules)."""
    w = R['word_map'].get(word, word)
    for pat, rep in R['suffix_rules']:
        if w not in R['fa']:
            w = re.sub(pat, rep, w)
    return w

def letters(w, word_initial=True):
    out = []
    n = len(w)
    for i, c in enumerate(w):
        first = word_initial and i == 0
        last = i == n - 1
        if c in VOW:
            if first:
                out.append(R['initial'].get(c, R['letters'].get(c, '')))
                continue
            if i > 0 and w[i - 1] in VOW:        # vowel after vowel: hamza seat
                out.append('ئ' if c not in 'oöuü' else 'ئو')
                continue
            if last and c in R['final']:
                out.append(R['final'][c]); continue
            out.append(R['letters'].get(c, ''))
        else:
            out.append(R['letters'].get(c, c))
    return ''.join(out)

def join(stem_fa, suffix_fa):
    if not suffix_fa: return stem_fa
    if stem_fa[-1] in 'هعء' or (stem_fa.endswith('ی') and suffix_fa.startswith('ی')):
        return stem_fa + ZWNJ + suffix_fa
    return stem_fa + suffix_fa

def word_fa(w):
    if w.isdigit(): return w.translate(FA_DIG)
    if w in R['fa']: return R['fa'][w]
    # longest known stem + transliterated suffix
    for k in range(len(w) - 1, 2, -1):
        stem = w[:k]
        if stem in R['fa']:
            return join(R['fa'][stem], letters(w[k:], word_initial=False))
    return letters(w)

TOKEN = re.compile(r"([A-Za-zƏəÖöÜüĞğİıIŞşÇçXxQq'-]+|\d+|[^\sA-Za-z\d]+)")

def convert(text):
    """returns (tabriz_latin, persian_script)"""
    lat, fa = [], []
    for part in text.split():
        toks = TOKEN.findall(part)
        lp, fp = '', ''
        for t in toks:
            if re.match(r"[A-Za-zƏəÖöÜüĞğİıIŞşÇçXxQq]", t):
                w = normalize(lower(t).replace("'", ''))
                lp += w
                fp += word_fa(w)
            elif t.isdigit():
                lp += t; fp += t.translate(FA_DIG)
            else:
                lp += t; fp += ''.join(R['punct'].get(c, c) for c in t)
        lat.append(lp); fa.append(fp)
    return ' '.join(lat), ' '.join(fa)

if __name__ == '__main__':
    for line in sys.stdin:
        if line.strip(): print(convert(line.strip())[1])
