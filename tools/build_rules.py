# Builds tabriz_rules.json (shared by the Python reference and the Android app)
import json
# 1) Baku / Turkey forms that whisper writes -> Tabriz spoken form (Latin). Same-meaning swaps only.
word_map = {
 "sonra":"sora","sonrakı":"sorakı","yenə":"genə","yine":"genə","nə vaxt":"haçan","harada":"harda","haradan":"hardan","burada":"burda","orada":"orda",
 "bəlkə":"şayəd","edir":"eliyir","edirəm":"eliyirəm","edirik":"eliyirik","edirsiz":"eliyirsiz","edirlər":"eliyirlər","etdi":"elədi","etdim":"elədim","etdilər":"elədilər",
 "etmək":"eləmək","etmir":"eləmir","eləyir":"eliyir","eləyirəm":"eliyirəm","eləyək":"eliyək","eləmiyib":"eləmiyib",
 "prezident":"rəis-cümhur","parlament":"məclis","seçki":"entexabat","seçkilər":"entexabat","sanksiya":"təhrim","sanksiyalar":"təhrimlər",
 "raket":"muşək","raketlər":"muşəklər","hücum":"həmlə","partlayış":"infijar","müharibə":"cəng","ölkə":"məmləkət","statistika":"amar","inflyasiya":"təvərröm",
 "problem":"müşkül","problemlər":"müşküllər","maraqlı":"calib","aptek":"daruxana","kartof":"yeralma","neft":"nəft","amerika":"amrika","israil":"esrail",
 "avtobus":"otobus","universitet":"daneşqah","məktəb":"mədrəsə","mağaza":"dükan","həkim":"doxtur","xəstəxana":"bimarestan",
 # Turkey-Turkish spellings whisper often outputs
 "ben":"mən","sen":"sən","değil":"deyil","değiller":"deyirlər","akşam":"axşam","yukarı":"yuxarı","yukarıdır":"yuxarıdı","çok":"çox","için":"üçün","şimdi":"indi","güzel":"qəşəng",
 "selam":"salam","dostlar":"dostlar","teşekkür":"təşəkkür","tamam":"tamam"}
# 2) suffix rules (regex on the Latin Tabriz word)
suffix_rules = [["(d|t)(ı|i|u|ü)r$","\\1\\2"],["(d|t)(ı|i|u|ü)rlar$","\\1\\2lar"],["(d|t)(ı|i|u|ü)rlər$","\\1\\2lər"],["([ıiuü])n[ıiuü]z$","\\1z"]]
# 3) Persian-script spellings for loanwords and frequent words (Latin Tabriz stem -> Persian script)
fa = {}
def add(s):
    for line in s.strip().split('\n'):
        for pair in line.split('|'):
            pair=pair.strip()
            if not pair: continue
            a,b=pair.split('=',1); fa[a.strip()]=b.strip()
add("""
salam=سلام|aziz=عزیز|tabriz=تبریز|cün=گون|gün=گون|hafta=هفته|həftə=هفته|qarşınas=کارشناس|nəfqəş=نفتکش|tərəviz=تره‌وز|eləməliyik=ائله‌مه‌لییک|mənbə=منبع|əziz=عزیز|dost=دوست|xoş=خوش|gəldiz=گلدیز|təbriz=تبریز|urmu=اورمو|urmiyə=ارومیه|ərdəbil=اردبیل|tehran=تهران|səfhə=صفحه|xəbər=خبر|ümidvaram=اومیدوارام|bugün=بوگون
məclis=مجلیس|qanun=قانون|təsvib=تصویب|rəsmi=رسمی|mənbə=منبع|aban=آبان|ay=آی|icra=اجرا|eqtesad=اقتصاد|iqtisad=اقتصاد|karşenas=کارشناس|təvərröm=تورم|hələ=هله|yuxarı=یوخاری
mərdum=مردم|qiymət=قیمت|narazi=ناراضی|hormoz=هرمز|hormuz=هرمز|təngə=تنگه|dənə=دنه|nəftkeş=نفتکش|nəft=نفت|gecə=گئجه|saat=ساعات|toqif=توقیف|toqqif=توقیف|amma=آمما|heç=هئچ
təyid=تأیید|inanmaq=اینانماق|traktor=تراختور|axır=آخیر|dəqiqə=دقیقه|stadyom=استادیوم|ayağa=آیاغا|qol=گول|səs=سس|şəhər=شهر|siz=سیز|iş=ایش|düz=دوز|haçan=هاچان|hara=هارا|harda=هاردا
nəmənə=نه‌مه‌نه|dünən=دونن|axşam=آخشام|bazar=بازار|tərəvəz=تره‌وز|kilo=کیلو|yeralma=یئرآلما|dükançı=دوکانچی|dükan=دوکان|aqa=آقا|ağa=آقا|qiymətlər=قیمتلر|lap=لاپ|adam=آدام|ustad=اوستاد
gərək=گرک|sora=سورا|uşaq=اوشاق|uşax=اوشاخ|çillə=چیلله|qarpız=قارپیز|nar=نار|üzüm=اوزوم|fərvərdin=فروردین|ordibeheşt=اردیبهشت|xordad=خرداد|tir=تیر|mordad=مرداد|şəhrivər=شهریور|mehr=مهر
azər=آذر|dey=دی|bəhmən=بهمن|isfənd=اسفند|esfənd=اسفند|muşək=موشک|həmlə=حمله|bimarestan=بیمارستان|nəfər=نفر|məsul=مسئول|nigaran=نیگاران|vəziyyət=وضعیت|vəziyət=وضعیت|kontrol=کنترول
video=ویدئو|vidyo=ویدئو|dostlar=دوستلار|fikir=فیکیر|fikriz=فیکریز|kament=کامنت|kamentdə=کامنتده|yazın=یازین|yaşayasız=یاشایاسیز|var=وار|olasız=اولاسیز|rəis-cümhur=رئیس‌جمهور|cümhur=جمهور
entexabat=انتخابات|təhrim=تحریم|təvafüq=توافق|qərardad=قرارداد|mozakirə=مذاکره|mərz=مرز|məntəqə=منطقه|ostan=استان|məmləkət=مملکت|dəxalət=دخالت|təhdid=تهدید|hoşe=هوش|məsnui=مصنوعی
amar=آمار|dərsəd=درصد|gozariş=گزارش|şirkət=شیرکت|rəsanə=رسانه|xəlic=خلیج|fars=فارس|dərya=دریا|mohasirə=محاصره|infijar=انفجار|cəng=جنگ|ərtəş=ارتش|sərbaz=سرباز|sepah=سپاه
atəşbəs=آتش‌بس|əmniyət=امنیت|səfarət=سفارت|vəzir=وزیر|vəzarət=وزارت|nümayəndə=نماینده|şayiə=شایعه|fəzaye=فضای|məcazi=مجازی|calib=جالب|müşkül=مشکل|daruxana=داروخانا|doxtur=دوختور
daneşqah=دانشگاه|mədrəsə=مدرسه|otobus=اوتوبوس|amrika=آمریکا|esrail=اسرائیل|tümən=تومن|dolar=دولار|həştad=هشتاد|məsələn=مثلاً|vaqiən=واقعاً|xülasə=خلاصه|bələxərə=بالاخره|təqribən=تقریباً
tramp=ترامپ|mask=ماسک|ilan=ایلان|rubio=روبیو|kuba=کوبا|florida=فلوریدا|vaşinqton=واشینگتن|rəis=رئیس|cələsə=جلسه|əməliyyat=عملیات|hesab=حساب|xaricə=خارجه|gəmi=گمی|qırağ=قیراغ
dəfə=دفعه|ciddi=جدی|söz=سؤز|işarə=اشاره|tamamlamaq=تامامالاماق|hədəf=هدف|kabel=کابل|internet=اینترنت|bank=بانک|onlayn=آنلاین|xidmət=خیدمت|dünya=دونیا|təsir=تأثیر|böyük=بؤیوک
pişbini=پیش‌بینی|tolid=تولید|təğyirat=تغییرات|robot=روبات|milyon=میلیون|milyard=میلیارد|insan=اینسان|ağıl=عاغیل|maşın=ماشین|müsahibə=مصاحبه|forbs=فوربس|bütün=بوتون|yer=یئر
sizin=سیزین|bizim=بیزیم|mən=من|sən=سن|o=او|biz=بیز|onlar=اونلار|bu=بو|şu=شو|belə=بئله|elə=ائله|ki=کی|da=دا|də=ده|və=و|ya=یا|ilə=ایله|üçün=اوچون|görə=گؤره|kimi=کیمی|indi=ایندی
deyir=دئییر|deyirlər=دئییرلر|dedi=دئدی|dedim=دئدیم|eliyir=ائلیر|eliyirlər=ائلیرلر|elədi=ائله‌دی|eləmiyib=ائله‌میب|eliyək=ائلییک|eləmək=ائله‌مک|oldu=اولدو|olacaq=اولاجاق|olsun=اولسون
çox=چوخ|tsox=چوخ|üç=اوچ|üts=اوچ|bir=بیر|iki=ایکی|dörd=دؤرد|beş=بئش|altı=آلتی|yeddi=یئددی|səkkiz=سککیز|doqquz=دوققوز|on=اون|iyirmi=ایگیرمی|otuz=اوتوز|qırx=قیرخ|əlli=اللی
altmış=آلتمیش|yetmiş=یئتمیش|doxsan=دوخسان|yüz=یوز|min=مین|ikimin=ایکی مین|hamı=هامی|hamma=هامی|getmişdim=گئتمیشدیم|aldıq=آلدیق|aldım=آلدیم|sabah=صاباح|xanım=خانیم|əmi=عمی
""")
letters = {"a":"ا","b":"ب","c":"ج","ç":"چ","d":"د","e":"ئ","ə":"","f":"ف","g":"گ","ğ":"غ","h":"ه","x":"خ","ı":"ی","i":"ی","j":"ژ","k":"ک","q":"ق","l":"ل","m":"م","n":"ن",
 "o":"و","ö":"ؤ","p":"پ","r":"ر","s":"س","ş":"ش","t":"ت","u":"و","ü":"و","v":"و","y":"ی","z":"ز","w":"و"}
initial = {"a":"آ","ə":"ا","e":"ائ","i":"ای","ı":"ای","o":"او","u":"او","ü":"او","ö":"اؤ"}
final = {"ə":"ه","e":"ه","a":"ا"}
json.dump({"version":1,"word_map":word_map,"suffix_rules":suffix_rules,"fa":fa,"letters":letters,"initial":initial,"final":final,
           "punct":{",":"،","?":"؟",";":"؛"}},open('tabriz_rules.json','w'),ensure_ascii=False,indent=0)
print(len(word_map),'word_map',len(fa),'fa')
