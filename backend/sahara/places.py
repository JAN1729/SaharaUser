"""A deliberately bounded seed catalogue, not a claim of nationwide coverage."""
import re
from .domain import Terminal, Transfer

_ROWS = [
    # Kerala Rail & Bus
    ('ers','Ernakulam Junction','Kochi',9.969,76.291,'rail',{'rail':'ERS'},['Kochi','Ernakulam','Cochin','കൊച്ചി','എറണാകുളം','ERS','Idukki','Munnar'],'Kerala'),
    ('ernakulam-town','Ernakulam Town','Kochi',9.991,76.289,'rail',{'rail':'ERN'},['Ernakulam Town','Ernakulam North','ERN'],'Kerala'),
    ('kty','Kottayam Railway Station','Kottayam',9.591,76.532,'rail',{'rail':'KTYM'},['Kottayam','കോട്ടയം','KTYM'],'Kerala'),
    ('tvc','Thiruvananthapuram Central','Thiruvananthapuram',8.488,76.952,'rail',{'rail':'TVC'},['Trivandrum','Thiruvananthapuram','തിരുവനന്തപുരം','TVM','TVC','Thampanoor'],'Kerala'),
    ('clt','Kozhikode Railway Station','Kozhikode',11.246,75.782,'rail',{'rail':'CLT'},['Kozhikode','Calicut','കോഴിക്കോട്','കാലിക്കറ്റ്','CLT','Wayanad','Kalpetta','Sulthan Bathery','Mananthavady'],'Kerala'),
    ('tcr','Thrissur Railway Station','Thrissur',10.518,76.214,'rail',{'rail':'TCR'},['Thrissur','Trichur','തൃശ്ശൂർ','TCR'],'Kerala'),
    ('pgt','Palakkad Junction','Palakkad',10.828,76.655,'rail',{'rail':'PGT'},['Palakkad','Palghat','പാലക്കാട്','PGT'],'Kerala'),
    ('srr','Shoranur Junction','Shoranur',10.760,76.280,'rail',{'rail':'SRR'},['Shoranur','ഷൊർണ്ണൂർ','SRR'],'Kerala'),
    ('can','Kannur Railway Station','Kannur',11.875,75.373,'rail',{'rail':'CAN'},['Kannur','Cannanore','കണ്ണൂർ','CAN'],'Kerala'),
    ('qln','Kollam Junction','Kollam',8.883,76.598,'rail',{'rail':'QLN'},['Kollam','Quilon','കൊല്ലം','QLN'],'Kerala'),
    ('allp','Alappuzha Railway Station','Alappuzha',9.492,76.326,'rail',{'rail':'ALLP'},['Alappuzha','Alleppey','ആലപ്പുഴ','ALLP'],'Kerala'),
    ('kgq','Kasaragod Railway Station','Kasaragod',12.505,74.990,'rail',{'rail':'KGQ'},['Kasaragod','കാസർഗോഡ്','KGQ'],'Kerala'),
    ('tir','Tirur Railway Station','Tirur',10.916,75.922,'rail',{'rail':'TIR'},['Tirur','തിരൂർ','TIR','Malappuram','Manjeri','Perinthalmanna'],'Kerala'),
    ('cngr','Chengannur Railway Station','Chengannur',9.317,76.612,'rail',{'rail':'CNGR'},['Chengannur','Pathanamthitta','Adoor','Pandalam','CNGR'],'Kerala'),
    ('awy','Aluva Railway Station','Aluva',10.108,76.353,'rail',{'rail':'AWY'},['Aluva','Alwaye','Angamaly','AWY'],'Kerala'),
    ('kcvl','Kochuveli Railway Station','Thiruvananthapuram',8.514,76.907,'rail',{'rail':'KCVL'},['Kochuveli','KCVL'],'Kerala'),
    ('tly','Thalassery Railway Station','Thalassery',11.751,75.495,'rail',{'rail':'TLY'},['Thalassery','Tellicherry','TLY'],'Kerala'),
    ('bdj','Vadakara Railway Station','Vadakara',11.602,75.590,'rail',{'rail':'BDJ'},['Vadakara','Badagara','BDJ'],'Kerala'),
    ('kze','Kanhangad Railway Station','Kanhangad',12.308,75.088,'rail',{'rail':'KZE'},['Kanhangad','KZE'],'Kerala'),
    ('guv','Guruvayur Railway Station','Guruvayur',10.596,76.046,'rail',{'rail':'GUV'},['Guruvayur','Guruvayoor','GUV'],'Kerala'),
    ('otp','Ottappalam Railway Station','Ottappalam',10.771,76.376,'rail',{'rail':'OTP'},['Ottappalam','Ottapalam','OTP'],'Kerala'),
    ('ktu','Kuttippuram Railway Station','Kuttippuram',10.849,76.031,'rail',{'rail':'KTU'},['Kuttippuram','Kuttipuram','KTU'],'Kerala'),
    ('nil','Nilambur Road Railway Station','Nilambur',11.278,76.226,'rail',{'rail':'NIL'},['Nilambur','Nilambur Road','NIL'],'Kerala'),
    ('ernakulam-bus','Ernakulam KSRTC Bus Station','Kochi',9.977,76.291,'bus',{'ksrtc':'449|Ernakulam (7)'},[],'Kerala'),
    ('kottayam-bus','Kottayam KSRTC Bus Station','Kottayam',9.589,76.519,'bus',{'ksrtc':'439|Kottayam (5)'},[],'Kerala'),
    ('trivandrum-bus','Thampanoor KSRTC Bus Station','Thiruvananthapuram',8.487,76.951,'bus',{'ksrtc':'443|Trivandrum (1)'},[],'Kerala'),
    ('kozhikode-bus','Kozhikode KSRTC boarding area','Kozhikode',11.258,75.787,'bus',{'ksrtc':'10072|Kozhikode (11)'},[],'Kerala'),

    # Tamil Nadu
    ('mas','Chennai Central','Chennai',13.082,80.275,'rail',{'rail':'MAS'},['Chennai','Chennai Central','Madras','Madras Central','MAS'],'Tamil Nadu'),
    ('ms','Chennai Egmore','Chennai',13.080,80.261,'rail',{'rail':'MS'},['Chennai Egmore','Egmore','MS'],'Tamil Nadu'),
    ('cbe','Coimbatore Junction','Coimbatore',10.996,76.967,'rail',{'rail':'CBE'},['Coimbatore','CBE'],'Tamil Nadu'),
    ('mdu','Madurai Junction','Madurai',9.919,78.111,'rail',{'rail':'MDU'},['Madurai','MDU'],'Tamil Nadu'),
    ('tpj','Tiruchirappalli Junction','Tiruchirappalli',10.793,78.685,'rail',{'rail':'TPJ'},['Tiruchirappalli','Trichy','TPJ'],'Tamil Nadu'),
    ('sa','Salem Junction','Salem',11.670,78.125,'rail',{'rail':'SA'},['Salem','SA'],'Tamil Nadu'),
    ('ed','Erode Junction','Erode',11.341,77.728,'rail',{'rail':'ED'},['Erode','ED'],'Tamil Nadu'),
    ('tup','Tiruppur Railway Station','Tiruppur',11.108,77.341,'rail',{'rail':'TUP'},['Tiruppur','Tirupur','TUP'],'Tamil Nadu'),
    ('ten','Tirunelveli Junction','Tirunelveli',8.730,77.707,'rail',{'rail':'TEN'},['Tirunelveli','Nellai','TEN'],'Tamil Nadu'),
    ('kpd','Katpadi Junction','Vellore',12.973,79.138,'rail',{'rail':'KPD'},['Katpadi','Vellore','KPD'],'Tamil Nadu'),
    ('ncj','Nagercoil Junction','Nagercoil',8.174,77.433,'rail',{'rail':'NCJ'},['Nagercoil','NCJ'],'Tamil Nadu'),
    ('cape','Kanniyakumari Railway Station','Kanniyakumari',8.082,77.549,'rail',{'rail':'CAPE'},['Kanniyakumari','Kanyakumari','CAPE'],'Tamil Nadu'),
    ('dg','Dindigul Junction','Dindigul',10.366,77.980,'rail',{'rail':'DG'},['Dindigul','DG'],'Tamil Nadu'),
    ('coimbatore-bus','Coimbatore KSRTC boarding area','Coimbatore',11.016,76.966,'bus',{'ksrtc':'386|Coimbatore'},[],'Tamil Nadu'),

    # Karnataka
    ('sbc','KSR Bengaluru','Bengaluru',12.977,77.569,'rail',{'rail':'SBC'},['Bengaluru','Bangalore','Bangalore City','KSR Bengaluru','ബെംഗളൂരു','SBC'],'Karnataka'),
    ('ypr','Yesvantpur Junction','Bengaluru',13.023,77.550,'rail',{'rail':'YPR'},['Yesvantpur','Yeshvantpur','YPR'],'Karnataka'),
    ('mys','Mysuru Junction','Mysuru',12.316,76.645,'rail',{'rail':'MYS'},['Mysuru','Mysore','MYS'],'Karnataka'),
    ('maq','Mangaluru Central','Mangaluru',12.865,74.843,'rail',{'rail':'MAQ'},['Mangaluru','Mangalore','Mangalore Central','MAQ'],'Karnataka'),
    ('maj','Mangaluru Junction','Mangaluru',12.870,74.878,'rail',{'rail':'MAJN'},['Mangaluru Junction','Mangalore Junction','MAJN'],'Karnataka'),
    ('ubl','SSS Hubballi Junction','Hubballi',15.352,75.148,'rail',{'rail':'UBL'},['Hubballi','Hubli','UBL'],'Karnataka'),
    ('ud','Udupi Railway Station','Udupi',13.336,74.755,'rail',{'rail':'UD'},['Udupi','UD','Manipal'],'Karnataka'),
    ('bgm','Belagavi Railway Station','Belagavi',15.859,74.509,'rail',{'rail':'BGM'},['Belagavi','Belgaum','BGM'],'Karnataka'),
    ('kawr','Karwar Railway Station','Karwar',14.819,74.135,'rail',{'rail':'KAWR'},['Karwar','KAWR'],'Karnataka'),
    ('bay','Ballari Junction','Ballari',15.143,76.921,'rail',{'rail':'BAY'},['Ballari','Bellary','BAY'],'Karnataka'),
    ('klbg','Kalaburagi Junction','Kalaburagi',17.330,76.837,'rail',{'rail':'KLBG'},['Kalaburagi','Gulbarga','KLBG'],'Karnataka'),
    ('dvg','Davangere Railway Station','Davangere',14.464,75.922,'rail',{'rail':'DVG'},['Davangere','Davanagere','DVG'],'Karnataka'),
    ('bengaluru-bus','Bengaluru KSRTC boarding area','Bengaluru',12.977,77.572,'bus',{'ksrtc':'298|Bangalore'},[],'Karnataka'),

    # Maharashtra
    ('csmt','Mumbai CSMT','Mumbai',18.940,72.835,'rail',{'rail':'CSMT'},['Mumbai','Bombay','Chhatrapati Shivaji Maharaj Terminus','CSMT','VT'],'Maharashtra'),
    ('ltt','Lokmanya Tilak Terminus','Mumbai',19.070,72.892,'rail',{'rail':'LTT'},['Lokmanya Tilak Terminus Mumbai','LTT','Kurla Terminus'],'Maharashtra'),
    ('mmct','Mumbai Central','Mumbai',18.969,72.819,'rail',{'rail':'MMCT'},['Mumbai Central','MMCT','BCT'],'Maharashtra'),
    ('pune','Pune Junction','Pune',18.528,73.874,'rail',{'rail':'PUNE'},['Pune','Poona','PUNE'],'Maharashtra'),
    ('ngp','Nagpur Junction','Nagpur',21.152,79.088,'rail',{'rail':'NGP'},['Nagpur','NGP'],'Maharashtra'),
    ('nk','Nashik Road','Nashik',19.957,73.834,'rail',{'rail':'NK'},['Nashik','Nasik','Nashik Road','NK'],'Maharashtra'),
    ('awb','Chhatrapati Sambhajinagar','Chhatrapati Sambhajinagar',19.863,75.328,'rail',{'rail':'AWB'},['Aurangabad','Chhatrapati Sambhajinagar','AWB'],'Maharashtra'),
    ('sur','Solapur Railway Station','Solapur',17.659,75.906,'rail',{'rail':'SUR'},['Solapur','Sholapur','SUR'],'Maharashtra'),

    # Delhi / NCR
    ('ndls','New Delhi Railway Station','Delhi',28.643,77.219,'rail',{'rail':'NDLS'},['Delhi','New Delhi','NDLS'],'Delhi'),
    ('dli','Old Delhi Railway Station','Delhi',28.661,77.228,'rail',{'rail':'DLI'},['Old Delhi','Delhi Junction','DLI'],'Delhi'),
    ('nzm','Hazrat Nizamuddin','Delhi',28.588,77.253,'rail',{'rail':'NZM'},['Hazrat Nizamuddin','Nizamuddin','NZM'],'Delhi'),
    ('anvt','Anand Vihar Terminal','Delhi',28.650,77.315,'rail',{'rail':'ANVT'},['Anand Vihar','ANVT'],'Delhi'),

    # Telangana & Andhra Pradesh
    ('sc','Secunderabad Junction','Hyderabad',17.434,78.501,'rail',{'rail':'SC'},['Hyderabad','Secunderabad','SC'],'Telangana'),
    ('hyb','Hyderabad Deccan','Hyderabad',17.392,78.470,'rail',{'rail':'HYB'},['Hyderabad Deccan','Nampally','HYB'],'Telangana'),
    ('wl','Warangal Railway Station','Warangal',17.962,79.605,'rail',{'rail':'WL'},['Warangal','Kazipet','WL','KZJ'],'Telangana'),
    ('bza','Vijayawada Junction','Vijayawada',16.518,80.620,'rail',{'rail':'BZA'},['Vijayawada','Bezawada','BZA'],'Andhra Pradesh'),
    ('vskp','Visakhapatnam Junction','Visakhapatnam',17.721,83.287,'rail',{'rail':'VSKP'},['Visakhapatnam','Vizag','VSKP'],'Andhra Pradesh'),
    ('tpty','Tirupati Railway Station','Tirupati',13.628,79.419,'rail',{'rail':'TPTY'},['Tirupati','TPTY'],'Andhra Pradesh'),
    ('gnt','Guntur Junction','Guntur',16.297,80.443,'rail',{'rail':'GNT'},['Guntur','GNT'],'Andhra Pradesh'),
    ('nlr','Nellore Railway Station','Nellore',14.444,79.988,'rail',{'rail':'NLR'},['Nellore','NLR'],'Andhra Pradesh'),

    # West Bengal
    ('hwh','Howrah Junction','Kolkata',22.583,88.342,'rail',{'rail':'HWH'},['Kolkata','Calcutta','Howrah','HWH'],'West Bengal'),
    ('sdah','Sealdah Railway Station','Kolkata',22.568,88.371,'rail',{'rail':'SDAH'},['Sealdah','SDAH'],'West Bengal'),

    # Gujarat
    ('adi','Ahmedabad Junction','Ahmedabad',23.023,72.601,'rail',{'rail':'ADI'},['Ahmedabad','Ahmadabad','ADI','Kalupur'],'Gujarat'),
    ('st','Surat Railway Station','Surat',21.205,72.841,'rail',{'rail':'ST'},['Surat','ST'],'Gujarat'),
    ('brc','Vadodara Junction','Vadodara',22.311,73.181,'rail',{'rail':'BRC'},['Vadodara','Baroda','BRC'],'Gujarat'),
    ('rjt','Rajkot Junction','Rajkot',22.311,70.795,'rail',{'rail':'RJT'},['Rajkot','RJT'],'Gujarat'),
    ('bvp','Bhavnagar Terminus','Bhavnagar',21.770,72.140,'rail',{'rail':'BVP'},['Bhavnagar','BVP'],'Gujarat'),

    # Rajasthan
    ('jp','Jaipur Junction','Jaipur',26.920,75.787,'rail',{'rail':'JP'},['Jaipur','JP','Pink City'],'Rajasthan'),
    ('ju','Jodhpur Junction','Jodhpur',26.287,73.018,'rail',{'rail':'JU'},['Jodhpur','JU'],'Rajasthan'),
    ('udz','Udaipur City','Udaipur',24.577,73.698,'rail',{'rail':'UDZ'},['Udaipur','Udaipur City','UDZ'],'Rajasthan'),
    ('kota','Kota Junction','Kota',25.218,75.864,'rail',{'rail':'KOTA'},['Kota','KOTA'],'Rajasthan'),
    ('aii','Ajmer Junction','Ajmer',26.452,74.639,'rail',{'rail':'AII'},['Ajmer','AII'],'Rajasthan'),
    ('bkn','Bikaner Junction','Bikaner',28.016,73.317,'rail',{'rail':'BKN'},['Bikaner','BKN'],'Rajasthan'),

    # Uttar Pradesh
    ('lko','Lucknow Charbagh','Lucknow',26.832,80.923,'rail',{'rail':'LKO'},['Lucknow','Charbagh','LKO'],'Uttar Pradesh'),
    ('cnb','Kanpur Central','Kanpur',26.454,80.350,'rail',{'rail':'CNB'},['Kanpur','CNB'],'Uttar Pradesh'),
    ('bsb','Varanasi Junction','Varanasi',25.328,82.986,'rail',{'rail':'BSB'},['Varanasi','Banaras','Kashi','BSB'],'Uttar Pradesh'),
    ('pryj','Prayagraj Junction','Prayagraj',25.443,81.834,'rail',{'rail':'PRYJ'},['Prayagraj','Allahabad','PRYJ'],'Uttar Pradesh'),
    ('agc','Agra Cantt','Agra',27.159,78.008,'rail',{'rail':'AGC'},['Agra','Agra Cantt','AGC'],'Uttar Pradesh'),
    ('gkp','Gorakhpur Junction','Gorakhpur',26.759,83.382,'rail',{'rail':'GKP'},['Gorakhpur','GKP'],'Uttar Pradesh'),
    ('be','Bareilly Junction','Bareilly',28.341,79.414,'rail',{'rail':'BE'},['Bareilly','BE'],'Uttar Pradesh'),
    ('gzb','Ghaziabad Junction','Ghaziabad',28.665,77.432,'rail',{'rail':'GZB'},['Ghaziabad','GZB'],'Uttar Pradesh'),
    ('mb','Moradabad Junction','Moradabad',28.832,78.777,'rail',{'rail':'MB'},['Moradabad','MB'],'Uttar Pradesh'),
    ('aljn','Aligarh Junction','Aligarh',27.892,78.077,'rail',{'rail':'ALJN'},['Aligarh','ALJN'],'Uttar Pradesh'),
    ('mtj','Mathura Junction','Mathura',27.487,77.676,'rail',{'rail':'MTJ'},['Mathura','MTJ'],'Uttar Pradesh'),
    ('vglj','VGL Jhansi Junction','Jhansi',25.448,78.556,'rail',{'rail':'VGLJ'},['Jhansi','VGL Jhansi','VGLJ'],'Uttar Pradesh'),
    ('ayc','Ayodhya Cantt','Ayodhya',26.776,82.138,'rail',{'rail':'AYC'},['Ayodhya','Ayodhya Cantt','Faizabad','AYC'],'Uttar Pradesh'),

    # Bihar
    ('pnbe','Patna Junction','Patna',25.602,85.137,'rail',{'rail':'PNBE'},['Patna','PNBE'],'Bihar'),
    ('gaya','Gaya Junction','Gaya',24.804,84.999,'rail',{'rail':'GAYA'},['Gaya','GAYA'],'Bihar'),
    ('mfp','Muzaffarpur Junction','Muzaffarpur',26.122,85.385,'rail',{'rail':'MFP'},['Muzaffarpur','MFP'],'Bihar'),

    # Madhya Pradesh
    ('bpl','Bhopal Junction','Bhopal',23.268,77.412,'rail',{'rail':'BPL'},['Bhopal','BPL'],'Madhya Pradesh'),
    ('indb','Indore Junction','Indore',22.717,75.868,'rail',{'rail':'INDB'},['Indore','INDB'],'Madhya Pradesh'),
    ('gwl','Gwalior Junction','Gwalior',26.216,78.196,'rail',{'rail':'GWL'},['Gwalior','GWL'],'Madhya Pradesh'),
    ('jbp','Jabalpur Junction','Jabalpur',23.161,79.950,'rail',{'rail':'JBP'},['Jabalpur','JBP'],'Madhya Pradesh'),
    ('ujn','Ujjain Junction','Ujjain',23.182,75.777,'rail',{'rail':'UJN'},['Ujjain','UJN'],'Madhya Pradesh'),

    # Punjab & Chandigarh
    ('cdg','Chandigarh Junction','Chandigarh',30.704,76.824,'rail',{'rail':'CDG'},['Chandigarh','CDG'],'Chandigarh'),
    ('asr','Amritsar Junction','Amritsar',31.634,74.865,'rail',{'rail':'ASR'},['Amritsar','ASR'],'Punjab'),
    ('ldh','Ludhiana Junction','Ludhiana',30.910,75.857,'rail',{'rail':'LDH'},['Ludhiana','LDH'],'Punjab'),

    # Chhattisgarh
    ('r','Raipur Junction','Raipur',21.258,81.630,'rail',{'rail':'R'},['Raipur','R'],'Chhattisgarh'),
    ('bsp','Bilaspur Junction','Bilaspur',22.067,82.164,'rail',{'rail':'BSP'},['Bilaspur','BSP'],'Chhattisgarh'),

    # Goa
    ('mao','Madgaon Junction','Madgaon',15.274,73.978,'rail',{'rail':'MAO'},['Goa','Madgaon','Margao','MAO'],'Goa'),
    ('vsg','Vasco da Gama Railway Station','Vasco da Gama',15.397,73.811,'rail',{'rail':'VSG'},['Vasco','Vasco da Gama','VSG'],'Goa'),
    ('thvm','Thivim Railway Station','Thivim',15.626,73.864,'rail',{'rail':'THVM'},['Thivim','North Goa','THVM'],'Goa'),

    # Odisha
    ('bbs','Bhubaneswar Railway Station','Bhubaneswar',20.267,85.844,'rail',{'rail':'BBS'},['Bhubaneswar','Bhubaneshwar','BBS'],'Odisha'),
    ('puri','Puri Railway Station','Puri',19.813,85.831,'rail',{'rail':'PURI'},['Puri','PURI'],'Odisha'),
    ('ctc','Cuttack Junction','Cuttack',20.463,85.894,'rail',{'rail':'CTC'},['Cuttack','CTC'],'Odisha'),
    ('rou','Rourkela Junction','Rourkela',22.227,84.858,'rail',{'rail':'ROU'},['Rourkela','ROU'],'Odisha'),

    # Assam
    ('ghy','Guwahati Railway Station','Guwahati',26.182,91.750,'rail',{'rail':'GHY'},['Guwahati','Gauhati','GHY'],'Assam'),
    ('dbrg','Dibrugarh Railway Station','Dibrugarh',27.473,94.912,'rail',{'rail':'DBRG'},['Dibrugarh','DBRG'],'Assam'),

    # Jammu & Kashmir
    ('jat','Jammu Tawi','Jammu',32.706,74.880,'rail',{'rail':'JAT'},['Jammu','Jammu Tawi','JAT'],'Jammu and Kashmir'),

    # Uttarakhand & Himachal Pradesh
    ('ddn','Dehradun Railway Station','Dehradun',30.316,78.032,'rail',{'rail':'DDN'},['Dehradun','DDN'],'Uttarakhand'),
    ('hw','Haridwar Railway Station','Haridwar',29.945,78.150,'rail',{'rail':'HW'},['Haridwar','HW'],'Uttarakhand'),
    ('rk','Roorkee Railway Station','Roorkee',29.855,77.889,'rail',{'rail':'RK'},['Roorkee','RK'],'Uttarakhand'),
    ('klk','Kalka Railway Station','Kalka',30.835,76.935,'rail',{'rail':'KLK'},['Kalka','Shimla','KLK'],'Himachal Pradesh'),

    # Jharkhand
    ('rnc','Ranchi Junction','Ranchi',23.350,85.334,'rail',{'rail':'RNC'},['Ranchi','RNC'],'Jharkhand'),
    ('tata','Tatanagar Junction','Jamshedpur',22.766,86.203,'rail',{'rail':'TATA'},['Tatanagar','Jamshedpur','TATA'],'Jharkhand'),
    ('dhn','Dhanbad Junction','Dhanbad',23.791,86.430,'rail',{'rail':'DHN'},['Dhanbad','DHN'],'Jharkhand'),

    # Airports
    ('ccj','Calicut International Airport','Kozhikode',11.137,75.955,'airport',{'iata':'CCJ'},['CCJ','Karipur Airport','Calicut Airport'],'Kerala'),
    ('cok','Cochin International Airport','Kochi',10.152,76.402,'airport',{'iata':'COK'},['COK','Cochin Airport','Nedumbassery Airport'],'Kerala'),
    ('blr','Kempegowda International Airport','Bengaluru',13.199,77.706,'airport',{'iata':'BLR'},['BLR','Bangalore Airport','Kempegowda Airport'],'Karnataka'),
    ('trv','Thiruvananthapuram International Airport','Thiruvananthapuram',8.482,76.920,'airport',{'iata':'TRV'},['TRV','Trivandrum Airport'],'Kerala'),
    ('maa','Chennai International Airport','Chennai',12.994,80.170,'airport',{'iata':'MAA'},['MAA','Chennai Airport'],'Tamil Nadu'),
    ('del','Delhi International Airport','Delhi',28.556,77.100,'airport',{'iata':'DEL'},['DEL','Delhi Airport','Indira Gandhi Airport'],'Delhi'),
    ('bom','Mumbai International Airport','Mumbai',19.090,72.866,'airport',{'iata':'BOM'},['BOM','Mumbai Airport','Chhatrapati Shivaji Airport'],'Maharashtra'),
    ('hyd','Hyderabad International Airport','Hyderabad',17.240,78.429,'airport',{'iata':'HYD'},['HYD','Hyderabad Airport','Rajiv Gandhi Airport'],'Telangana'),
]
TERMINALS = {row[0]: Terminal(id=row[0],name=row[1],city=row[2],lat=row[3],lng=row[4],
    kind=row[5],codes=row[6],aliases=row[7],state=row[8] if len(row)>8 else 'Kerala') for row in _ROWS}


def is_kerala(item):
    if not item:
        return False
    terminal = TERMINALS.get(item) if isinstance(item, str) else item
    if terminal is None:
        return False
    state = getattr(terminal, 'state', None) or 'Kerala'
    return state.strip().casefold() == 'kerala'


class CoverageError(ValueError):
    """A place lacks verified provider mappings; this is not an LLM outage."""


def _clean_token(s):
    s = s.strip().casefold()
    s = re.sub(r'^(?:from|to|at|near|around)\s+', '', s)
    s = re.sub(r'\b(?:railway station|railway stn|train station|rly stn|stn|station|bus station|bus stand|bus stop|bus terminal|ksrtc bus stand|ksrtc stand|junction|jn|central|cantt|cantonment|terminal|term|city|airport|air port)\b', '', s)
    s = re.sub(r'[^\w\s]', ' ', s)
    return ' '.join(s.split())


def resolve(value):
    normalized = value.strip().casefold()

    state_prompts = {
        'gujarat': 'Gujarat is a state. Which city do you mean: Ahmedabad, Surat, Vadodara, or another city?',
        'kerala': 'Kerala is a state. Which city do you mean: Kozhikode, Kochi, Thiruvananthapuram, or another city?',
        'karnataka': 'Karnataka is a state. Which city do you mean: Bengaluru, Mysuru, Mangaluru, or another city?',
        'tamil nadu': 'Tamil Nadu is a state. Which city do you mean: Chennai, Coimbatore, Madurai, or another city?',
        'tamilnadu': 'Tamil Nadu is a state. Which city do you mean: Chennai, Coimbatore, Madurai, or another city?',
        'maharashtra': 'Maharashtra is a state. Which city do you mean: Mumbai, Pune, Nagpur, or another city?',
        'rajasthan': 'Rajasthan is a state. Which city do you mean: Jaipur, Jodhpur, Udaipur, or another city?',
        'uttar pradesh': 'Uttar Pradesh is a state. Which city do you mean: Lucknow, Kanpur, Varanasi, Agra, or another city?',
    }
    if normalized in state_prompts:
        raise CoverageError(state_prompts[normalized])

    # 1. Exact ID
    if normalized in TERMINALS:
        return TERMINALS[normalized]

    # 2. Exact match in name, aliases, codes
    for terminal in TERMINALS.values():
        if normalized in [s.casefold() for s in [terminal.name, *terminal.aliases, *terminal.codes.values()]]:
            return terminal

    # 3. Exact match with city name (rail terminals preferred)
    for terminal in TERMINALS.values():
        if terminal.kind == 'rail' and normalized == terminal.city.casefold():
            return terminal

    # 4. Cleaned token match
    cleaned = _clean_token(normalized)
    if cleaned:
        for terminal in TERMINALS.values():
            candidates = [terminal.id.casefold(), terminal.city.casefold(), *[s.casefold() for s in [terminal.name, *terminal.aliases, *terminal.codes.values()]]]
            cleaned_candidates = [_clean_token(c) for c in candidates]
            if cleaned in candidates or cleaned in cleaned_candidates:
                return terminal

        # 5. Prefix/word containment match for queries >= 3 chars
        if len(cleaned) >= 3:
            for terminal in TERMINALS.values():
                city = terminal.city.casefold()
                if cleaned == city or city.startswith(cleaned) or cleaned.startswith(city):
                    return terminal
            for terminal in TERMINALS.values():
                for alias in terminal.aliases:
                    a = alias.casefold()
                    if len(a) >= 3 and (cleaned == a or a.startswith(cleaned) or cleaned.startswith(a)):
                        return terminal

    raise CoverageError('Place not in configured coverage: '+value.strip()[:100]+'. Choose a listed terminal or add verified provider mappings.')


def transfer_catalog():
    result=[]
    for a,b,minutes,cost,mode,distance in [
        ('ers','ernakulam-bus',18,0,'walk',1200),('kty','kottayam-bus',25,0,'walk',1800),
        ('tvc','trivandrum-bus',7,0,'walk',350),('sbc','bengaluru-bus',10,0,'walk',600),
        ('ers','cok',75,None,'road',0),('kty','cok',130,None,'road',0),('sbc','blr',100,None,'road',0),
        ('tvc','trv',35,None,'road',0),('cbe','coimbatore-bus',30,None,'road',0),
        ('clt','kozhikode-bus',25,None,'road',0),('clt','ccj',75,None,'road',0)]:
        for origin,destination in [(a,b),(b,a)]:
            result.append(Transfer(origin=origin,destination=destination,minutes=minutes,
                fare_minor=cost,mode=mode,distance_m=distance,evidence='estimated'))
    return result


def discovery_pairs(origin,destination):
    cities={TERMINALS[origin].city,TERMINALS[destination].city,'Kochi','Coimbatore'}
    origins=[t for t in TERMINALS.values() if t.city==TERMINALS[origin].city]
    destinations=[t for t in TERMINALS.values() if t.city==TERMINALS[destination].city]
    links=transfer_catalog()
    for existing,other in [(origins,destinations),(destinations,origins)]:
        ids={t.id for t in existing}
        accessible={link.destination for link in links if link.origin in ids and TERMINALS[link.destination].kind=='airport'}
        existing.extend(TERMINALS[id] for id in sorted(accessible-ids))
    hubs=[t for t in TERMINALS.values() if t.city in cities and t.kind in ('rail','bus')]
    pairs=[]
    for a in origins:
        for b in destinations:
            if a.kind==b.kind and a.id!=b.id:
                pairs.append((a,b))
    for h in hubs:
        for a in origins:
            if a.kind==h.kind and a.city!=h.city:
                pairs.append((a,h))
        for b in destinations:
            if b.kind==h.kind and b.city!=h.city:
                pairs.append((h,b))
    return list({(a.id,b.id):(a,b) for a,b in pairs}.values())[:18]
