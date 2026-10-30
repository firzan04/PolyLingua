package com.polylingua.edge.domain

import com.polylingua.edge.domain.model.VocabularyUpgrade

/**
 * B1/B2 -> C1/C2 lexical upgrade dictionary (600+ entries): generic intensified adjectives,
 * high-frequency verbs and nouns, hedging/opinion phrases, idioms, and topic-specific
 * collocations for all 34 IELTS thematic domains used by the curriculum generator.
 *
 * Entry format: "source|upgrade 1;upgrade 2;upgrade 3". Topic sections take priority over the
 * general section when the task topic matches, which keeps suggestions context-aware
 * (e.g. "job" -> "occupation" generally, but "career" in a Work & Careers task).
 */
object LexicalUpgradeDictionary {

    data class Entry(
        val source: String,
        val tokens: List<String>,
        val upgrades: List<String>,
        val topic: String,
        /** Safe as a drop-in replacement in automatic rewrites (adjectives, nouns, hedges). */
        val autoApply: Boolean,
    )

    private val SECTIONS: List<Pair<String, String>> = listOf(
        "general" to """
very important|paramount;crucial;of vital importance
very good|outstanding;excellent;exceptional
very bad|dreadful;appalling;atrocious
very big|enormous;immense;colossal
very small|minuscule;tiny;minute
very happy|overjoyed;elated;delighted
very sad|heartbroken;devastated;deeply upset
very difficult|extremely challenging;arduous;formidable
very easy|effortless;straightforward;a piece of cake
very interesting|fascinating;captivating;intriguing
very boring|tedious;mind-numbing;monotonous
very beautiful|stunning;breathtaking;exquisite
very ugly|hideous;unsightly;grotesque
very tired|exhausted;worn out;drained
very angry|furious;livid;incensed
very scared|terrified;petrified;horrified
very hot|scorching;sweltering;boiling
very cold|freezing;bitterly cold;icy
very fast|lightning-fast;rapid;swift
very slow|sluggish;painfully slow;unhurried
very old|ancient;antiquated;age-old
very new|brand-new;state-of-the-art;cutting-edge
very clean|spotless;immaculate;pristine
very dirty|filthy;grimy;squalid
very expensive|exorbitant;extortionate;prohibitively costly
very cheap|dirt cheap;inexpensive;a real bargain
very crowded|packed;overcrowded;teeming with people
very quiet|tranquil;serene;hushed
very loud|deafening;ear-splitting;thunderous
very rich|affluent;wealthy;well-off
very poor|destitute;impoverished;deprived
very clever|brilliant;ingenious;exceptionally bright
very stupid|foolish;absurd;senseless
very strong|robust;powerful;formidable
very weak|feeble;frail;fragile
very busy|swamped;snowed under;run off my feet
very famous|renowned;world-famous;celebrated
very popular|immensely popular;in high demand;widely acclaimed
very useful|invaluable;indispensable;highly beneficial
very dangerous|perilous;hazardous;treacherous
very safe|secure;risk-free;well protected
very serious|grave;severe;acute
very funny|hilarious;hysterical;side-splitting
very strange|bizarre;peculiar;odd
very common|widespread;prevalent;commonplace
very rare|scarce;exceptional;few and far between
very different|markedly different;poles apart;strikingly different
very similar|nearly identical;strikingly similar;much alike
very large|vast;massive;extensive
very long|lengthy;prolonged;extended
very short|brief;fleeting;concise
very high|soaring;sky-high;towering
very low|minimal;negligible;rock-bottom
very hard|gruelling;demanding;strenuous
very simple|uncomplicated;elementary;basic
very complicated|intricate;convoluted;highly complex
very clear|crystal clear;self-evident;unmistakable
very sure|certain;convinced;positive
very worried|anxious;apprehensive;deeply concerned
very excited|thrilled;exhilarated;over the moon
very surprised|astonished;astounded;taken aback
very pleased|delighted;thrilled;gratified
very upset|distraught;devastated;distressed
very nervous|on edge;jittery;apprehensive
very calm|composed;serene;unflappable
very healthy|in peak condition;fit as a fiddle;thriving
very sick|seriously ill;gravely ill;bedridden
very hungry|starving;ravenous;famished
very thirsty|parched;dehydrated;gasping for a drink
very bright|dazzling;brilliant;radiant
very dark|pitch-black;gloomy;murky
very nice|delightful;lovely;charming
very kind|generous;compassionate;considerate
very rude|offensive;disrespectful;insolent
very polite|courteous;respectful;gracious
very honest|sincere;upfront;candid
very lazy|idle;sluggish;indolent
very hardworking|industrious;diligent;conscientious
very successful|highly accomplished;thriving;prosperous
very effective|highly effective;potent;efficacious
very necessary|essential;imperative;indispensable
very wrong|completely mistaken;misguided;way off the mark
very much|tremendously;immensely;enormously
very well|exceptionally well;remarkably well;superbly
very quickly|rapidly;swiftly;in no time
pretty good|rather impressive;fairly decent;quite commendable
so many|such a vast number of;an overwhelming number of;countless
too much|excessive;an excess of;an overabundance of
more and more|an increasing number of;a growing number of;increasingly
good|beneficial;advantageous;favourable
bad|detrimental;harmful;adverse
big|substantial;considerable;significant
small|modest;limited;marginal
nice|pleasant;agreeable;delightful
important|crucial;essential;pivotal
interesting|engaging;thought-provoking;compelling
boring|dull;uninspiring;tedious
beautiful|picturesque;stunning;attractive
happy|content;cheerful;delighted
sad|disheartened;melancholy;downcast
hard|challenging;demanding;tough
easy|straightforward;manageable;effortless
difficult|challenging;problematic;daunting
great|remarkable;tremendous;superb
amazing|astonishing;extraordinary;phenomenal
terrible|dreadful;appalling;horrendous
awful|abysmal;dire;atrocious
fun|enjoyable;entertaining;amusing
funny|amusing;comical;witty
old|elderly;long-established;dated
new|novel;innovative;contemporary
different|distinct;diverse;dissimilar
same|identical;equivalent;uniform
many|numerous;a multitude of;countless
a lot of|a great deal of;a wealth of;an abundance of
lots of|plenty of;a host of;a wide array of
huge|vast;massive;monumental
tiny|minuscule;microscopic;compact
clever|astute;shrewd;intelligent
smart|sharp;quick-witted;astute
rich|affluent;prosperous;well-to-do
poor|underprivileged;disadvantaged;low-income
cheap|affordable;economical;budget-friendly
expensive|costly;pricey;overpriced
crowded|congested;densely populated;bustling
dangerous|hazardous;risky;precarious
safe|secure;protected;low-risk
healthy|wholesome;nutritious;in good shape
modern|contemporary;up-to-date;state-of-the-art
traditional|time-honoured;conventional;long-standing
famous|well-known;renowned;eminent
popular|widely liked;sought-after;trendy
useful|practical;handy;valuable
useless|futile;ineffective;pointless
necessary|essential;vital;requisite
clear|evident;apparent;transparent
strange|unusual;odd;unconventional
normal|typical;ordinary;standard
common|widespread;prevalent;frequent
quiet|peaceful;calm;tranquil
noisy|rowdy;boisterous;clamorous
busy|hectic;bustling;occupied
cool|impressive;stylish;awesome
okay|acceptable;satisfactory;adequate
perfect|ideal;flawless;impeccable
wonderful|marvellous;magnificent;splendid
strong|powerful;resilient;sturdy
weak|fragile;vulnerable;flimsy
fast|rapid;swift;speedy
slow|gradual;leisurely;sluggish
quickly|promptly;swiftly;rapidly
slowly|gradually;steadily;progressively
badly|poorly;inadequately;severely
nearly|almost;virtually;practically
enough|sufficient;adequate;ample
big problem|major issue;serious concern;pressing challenge
good effect|beneficial impact;positive influence;favourable outcome
bad effect|adverse effect;detrimental impact;negative consequence
big effect|profound effect;far-reaching impact;substantial influence
good point|valid point;compelling argument;fair observation
good idea|excellent suggestion;sensible proposal;brilliant notion
bad idea|ill-advised plan;misguided notion;unwise move
good thing|positive development;blessing;welcome change
bad thing|drawback;downside;setback
big change|dramatic shift;radical transformation;sweeping change
big role|pivotal role;significant part;key function
important part|integral part;cornerstone;key component
main reason|primary reason;principal factor;chief cause
solve the problem|address the issue;tackle the problem;resolve the matter
have a problem|face a challenge;encounter difficulties;run into trouble
it is true|it is undeniable;it holds true;there is no denying
thing|aspect;element;factor
things|matters;issues;elements
stuff|material;belongings;items
problem|issue;challenge;dilemma
problems|issues;challenges;difficulties
way|method;approach;means
people|individuals;citizens;the public
kids|children;youngsters;the younger generation
job|occupation;profession;career
money|finances;funds;capital
idea|notion;concept;proposal
opinion|viewpoint;perspective;stance
reason|rationale;justification;motive
result|outcome;consequence;upshot
effect|impact;influence;consequence
advantage|benefit;merit;upside
disadvantage|drawback;downside;shortcoming
chance|opportunity;prospect;possibility
place|location;venue;spot
part|component;segment;portion
kind of|type of;form of;variety of
type|category;genre;variety
situation|circumstances;scenario;state of affairs
life|lifestyle;existence;way of life
world|globe;international community;society at large
country|nation;state;homeland
goal|objective;aim;target
trip|journey;excursion;expedition
friend|companion;acquaintance;confidant
house|residence;home;property
weather|climate;conditions;forecast
car|vehicle;automobile;motor
teacher|educator;instructor;tutor
boss|manager;supervisor;line manager
worker|employee;member of staff;labourer
old people|the elderly;senior citizens;older generations
young people|the youth;young adults;the younger generation
everybody|everyone;all individuals;people across the board
everything|all aspects;every detail;the whole lot
i think|i believe;in my view;i would argue that
i believe|i'm convinced that;it's my firm belief that;i hold the view that
in my opinion|from my perspective;to my mind;as far as i'm concerned
i feel|i sense;my impression is that;i'm inclined to think
i guess|i suppose;i'd imagine;presumably
maybe|perhaps;possibly;conceivably
of course|naturally;undoubtedly;needless to say
to be honest|frankly;candidly;truth be told
basically|essentially;fundamentally;in essence
definitely|undoubtedly;without question;unquestionably
probably|in all likelihood;most likely;presumably
i don't know|i'm not entirely sure;i've never really considered that;that's an interesting question
i'm not sure|i'm uncertain;it's hard to say;i'm in two minds
i agree|i couldn't agree more;i concur;i'm of the same opinion
i disagree|i beg to differ;i'd have to disagree;i see it differently
as i said|as i mentioned earlier;as previously stated;to reiterate
and then|subsequently;after that;following that
for example|for instance;to illustrate;a case in point is
you know|as you might expect;as we all know;evidently
sort of|somewhat;in a way;more or less
a lot|considerably;substantially;a great deal
nowadays|in this day and age;these days;in the modern era
these days|currently;at present;in contemporary society
in the past|historically;in bygone days;in previous decades
in the future|down the line;in the years to come;in the long run
many people|a large proportion of people;a significant number of individuals;countless people
some people|certain individuals;a number of people;some members of the public
most people|the vast majority;the bulk of people;most individuals
at the moment|at present;currently;right now
all the time|constantly;continually;around the clock
sometimes|occasionally;from time to time;now and then
always|invariably;consistently;without fail
usually|typically;generally;as a rule
in the end|ultimately;eventually;in the long run
because of|due to;owing to;as a result of
extremely important|paramount;of the utmost importance;critical
really good|superb;first-rate;top-notch
really bad|dire;abysmal;deplorable
really big|gigantic;massive;enormous
really interesting|riveting;absorbing;fascinating
really difficult|daunting;gruelling;formidable
really important|essential;vital;key
quite good|fairly impressive;reasonably good;respectable
not good|unsatisfactory;subpar;far from ideal
not bad|reasonably good;decent;passable
not easy|far from straightforward;challenging;no easy feat
not enough|insufficient;inadequate;in short supply
a little bit|slightly;marginally;somewhat
a big number of|a substantial number of;a considerable number of;a host of
a small number of|a handful of;a minority of;a limited number of
the main problem|the crux of the matter;the core issue;the principal concern
the best way|the most effective approach;the optimal method;the ideal means
in general|on the whole;by and large;generally speaking
for me|personally;from my standpoint;as far as i'm concerned
right now|at present;currently;at this point in time
long time ago|in years gone by;long ago;many years ago
every day|on a daily basis;day in, day out;routinely
very often|frequently;regularly;time and again
at the same time|simultaneously;concurrently;meanwhile
on the internet|online;on the web;via digital platforms
in the world|globally;worldwide;across the globe
all over the world|worldwide;across the globe;internationally
good for health|beneficial to health;conducive to wellbeing;health-promoting
bad for health|detrimental to health;harmful to wellbeing;a health hazard
high price|steep price;exorbitant cost;premium price
low price|affordable price;budget price;competitive price
good job|rewarding career;well-paid position;fulfilling occupation
free time activities|leisure pursuits;recreational activities;pastimes
hard work|diligence;dedication;sustained effort
good memories|fond memories;cherished recollections;treasured memories
bad experience|unpleasant ordeal;negative encounter;setback
good experience|rewarding experience;memorable occasion;positive encounter
best friend|closest friend;confidant;kindred spirit
famous person|celebrity;public figure;household name
nice weather|pleasant weather;glorious sunshine;balmy conditions
bad weather|inclement weather;dismal weather;harsh conditions
""",
        "general~suggest" to """
get better|improve;make progress;pick up
get worse|deteriorate;decline;take a turn for the worse
get a job|secure employment;land a job;obtain a position
get money|earn an income;generate revenue;make a living
get information|obtain information;gather data;acquire knowledge
get married|tie the knot;wed;settle down
get angry|lose my temper;become irritated;fly off the handle
get bigger|expand;grow;increase in size
get older|age;grow older;mature
get used to|become accustomed to;adapt to;grow familiar with
get rid of|eliminate;dispose of;do away with
get along with|get on well with;have a good rapport with;see eye to eye with
make money|generate income;earn a living;turn a profit
make a decision|reach a decision;make up my mind;come to a conclusion
make a mistake|err;make an error;slip up
make friends|form friendships;build relationships;socialise
make sure|ensure;guarantee;verify
make better|enhance;improve;refine
make worse|exacerbate;aggravate;worsen
do research|conduct research;carry out a study;undertake an investigation
do business|conduct business;trade;engage in commerce
do exercise|work out;engage in physical activity;keep fit
do homework|complete assignments;revise;study
do my best|give it my all;go the extra mile;make every effort
think about|consider;contemplate;reflect on
talk about|discuss;address;touch upon
look at|examine;inspect;take a look at
look for|search for;seek;hunt for
find out|discover;learn;ascertain
go up|rise;climb;escalate
go down|fall;decline;plummet
try hard|make a concerted effort;go all out;strive
really like|adore;be passionate about;be keen on
deal with|cope with;handle;tackle
look after|take care of;care for;tend to
find it hard|struggle;find it challenging;have difficulty
come back|return;go back;make a comeback
go on|continue;carry on;persist
put off|postpone;delay;defer
think of|come up with;conceive;devise
point out|highlight;emphasise;draw attention to
say|state;mention;point out
tell|inform;notify;let someone know
show|demonstrate;illustrate;reveal
help|assist;support;facilitate
use|utilise;employ;make use of
give|provide;offer;grant
keep|maintain;retain;preserve
start|commence;initiate;launch
begin|commence;embark on;set out
stop|cease;halt;put an end to
finish|complete;conclude;wrap up
try|attempt;endeavour;strive
want|wish;desire;aspire to
need|require;necessitate;call for
love|adore;cherish;treasure
hate|loathe;detest;can't stand
see|notice;observe;witness
find|discover;come across;locate
know|be aware of;recognise;be familiar with
understand|comprehend;grasp;make sense of
learn|acquire;pick up;master
teach|educate;instruct;train
improve|enhance;boost;refine
increase|rise;surge;soar
decrease|decline;drop;diminish
change|alter;modify;transform
choose|select;opt for;pick
buy|purchase;acquire;invest in
sell|market;trade;retail
spend|expend;invest;devote
save|conserve;set aside;put by
earn|make;bring in;receive
pay|cover the cost of;fund;finance
build|construct;erect;develop
create|generate;produce;establish
solve|resolve;tackle;address
fix|repair;rectify;sort out
cause|trigger;lead to;bring about
affect|influence;impact;have a bearing on
support|back;endorse;advocate
protect|safeguard;shield;preserve
destroy|devastate;wreck;obliterate
damage|harm;impair;undermine
reduce|lessen;cut down on;curb
avoid|steer clear of;sidestep;refrain from
prevent|avert;forestall;deter
allow|permit;enable;authorise
ask|enquire;request;query
explain|clarify;elaborate on;account for
describe|depict;portray;outline
discuss|debate;deliberate;examine
agree|concur;see eye to eye;share the view
disagree|beg to differ;take issue with;dispute
prepare|get ready;gear up;make arrangements
develop|evolve;advance;cultivate
grow|expand;flourish;thrive
become|turn into;grow;evolve into
happen|occur;take place;arise
seem|appear;come across as;look
live|reside;dwell;inhabit
travel|journey;explore;venture
visit|tour;call on;drop in on
meet|encounter;come across;get together with
remember|recall;recollect;bear in mind
forget|overlook;neglect;lose track of
worry|fret;be concerned;feel anxious
very rarely|once in a blue moon;hardly ever;on rare occasions
work very hard|burn the midnight oil;keep my nose to the grindstone;go above and beyond
start again|go back to square one;make a fresh start;turn over a new leaf
understand well|have a good grasp of;get the hang of;know inside out
think the same|be on the same wavelength;see eye to eye;share the same view
try something new|step out of my comfort zone;branch out;broaden my horizons
learn new things|broaden my horizons;expand my knowledge;pick up new skills
be nervous|have butterflies in my stomach;be on edge;get cold feet
earn little money|make ends meet;live on a shoestring;scrape by
spend too much|splash out;break the bank;go overboard
study a lot|hit the books;cram;burn the midnight oil
it is clear|it goes without saying;it's self-evident;it stands to reason
costs a lot|costs an arm and a leg;is a drain on resources;comes at a steep price
very easy task|a piece of cake;child's play;a walk in the park
""",
        "education" to """
study hard|apply myself diligently;knuckle down;hit the books
good school|reputable institution;prestigious school;high-performing school
learn a lot|acquire a wealth of knowledge;make considerable progress;broaden my understanding
test|assessment;examination;evaluation
get good marks|achieve top grades;excel academically;attain high scores
school subjects|academic disciplines;curriculum subjects;areas of study
lazy students|disengaged learners;unmotivated students;underachievers
teacher|educator;tutor;lecturer
""",
        "environment" to """
pollution is bad|pollution poses a severe threat;pollution wreaks havoc;pollution is profoundly damaging
cut trees|deforestation;logging;clearing forests
rubbish|waste;litter;refuse
save energy|conserve energy;reduce energy consumption;cut down on energy use
dirty air|poor air quality;smog;airborne pollutants
protect nature|preserve natural habitats;conserve biodiversity;safeguard ecosystems
throw away plastic|discard single-use plastics;dispose of plastic waste;generate plastic waste
""",
        "technology" to """
phone|smartphone;mobile device;handset
use computers|harness digital tools;rely on computing technology;operate digital devices
new technology|cutting-edge technology;emerging technology;state-of-the-art innovation
online|digital;web-based;virtual
internet addiction|compulsive internet use;digital dependency;screen addiction
technology is useful|technology is indispensable;technology streamlines daily life;technology is a game-changer
""",
        "ai" to """
robots take jobs|automation displaces workers;AI renders some roles obsolete;machines replace human labour
smart computer|intelligent system;machine-learning model;AI-powered tool
ai is dangerous|AI poses ethical risks;AI raises serious concerns;AI could be misused
ai helps people|AI augments human capabilities;AI streamlines tasks;AI enhances productivity
computers think|algorithms make decisions;machines simulate reasoning;systems learn from data
fake videos|deepfakes;synthetic media;manipulated footage
""",
        "urbanization" to """
big city|metropolis;urban centre;sprawling city
traffic jam|gridlock;congestion;tailbacks
many buildings|high-rise developments;dense urban infrastructure;a concrete jungle
move to the city|migrate to urban areas;relocate to the city;join the urban migration
city life is stressful|urban living takes its toll;the hustle and bustle is draining;city life is hectic
village|rural community;countryside settlement;hamlet
""",
        "heritage" to """
old buildings|historic landmarks;heritage sites;centuries-old architecture
traditions|customs;cultural practices;time-honoured rituals
keep culture|preserve cultural heritage;uphold traditions;safeguard cultural identity
history is important|history shapes our identity;heritage connects generations;the past informs the present
traditional clothes|national dress;traditional attire;heritage costumes
museum|cultural institution;gallery;heritage centre
""",
        "healthcare" to """
healthy food|nutritious diet;balanced meals;wholesome food
get sick|fall ill;come down with something;contract an illness
do exercise|engage in physical activity;work out regularly;keep active
fat|overweight;obese;carrying excess weight
doctor|medical practitioner;physician;healthcare professional
hospital|healthcare facility;medical centre;clinic
medicine|medication;treatment;prescription drugs
""",
        "globalization" to """
countries work together|nations collaborate;international cooperation flourishes;cross-border partnerships form
foreign companies|multinational corporations;global brands;overseas firms
cultures mix|cultural exchange;cultural convergence;cross-cultural interaction
world is connected|the world is interconnected;a global village;an interdependent world
lose culture|cultural homogenisation;erosion of local identity;loss of cultural diversity
trade between countries|international trade;global commerce;cross-border trade
""",
        "crime" to """
bad people|offenders;criminals;wrongdoers
put in prison|incarcerate;imprison;sentence to jail
stop crime|deter crime;tackle criminal activity;crack down on crime
police|law enforcement;the authorities;the police force
punishment|penalty;sentence;sanction
break the law|commit an offence;violate the law;flout regulations
""",
        "media" to """
news|current affairs;news coverage;headlines
fake news|misinformation;disinformation;false reporting
tv|television;broadcast media;the small screen
newspaper|the press;print media;broadsheet
journalists|reporters;correspondents;the press
advertisements|commercials;marketing campaigns;adverts
""",
        "economics" to """
prices go up|inflation rises;prices soar;the cost of living increases
make money|generate profit;turn a profit;boost revenue
economy is bad|the economy is in decline;a recession;an economic downturn
save money|accumulate savings;put money aside;economise
rich countries|developed nations;high-income economies;affluent nations
poor countries|developing nations;low-income economies;emerging economies
""",
        "psychology" to """
stress|anxiety;pressure;mental strain
feel bad|feel down;be in low spirits;feel demoralised
mental health problems|mental health conditions;psychological disorders;emotional difficulties
be happy|thrive;flourish;experience wellbeing
relax|unwind;decompress;recharge
think positively|adopt a positive mindset;look on the bright side;stay optimistic
""",
        "space" to """
go to space|venture into space;explore outer space;embark on space missions
spend money on space|invest in space exploration;allocate funding to space programmes;fund space research
planets|celestial bodies;other worlds;planetary systems
rockets|spacecraft;launch vehicles;space shuttles
find life|discover extraterrestrial life;detect signs of life;find alien organisms
space is useless|space exploration is a waste of resources;space programmes are extravagant;space funding is misplaced
""",
        "architecture" to """
tall buildings|skyscrapers;high-rises;towering structures
nice building|architectural gem;striking structure;elegant edifice
build houses|construct housing;develop residential properties;erect homes
old style|classical style;traditional design;period architecture
modern style|contemporary design;minimalist architecture;cutting-edge design
design|layout;blueprint;architectural plan
""",
        "work" to """
job|career;profession;occupation
boss|employer;line manager;supervisor
salary|remuneration;income;wages
work hard|put in long hours;go the extra mile;work diligently
get fired|be dismissed;be laid off;be made redundant
find a job|secure employment;land a position;find work
work from home|telecommute;work remotely;work flexibly
""",
        "travel" to """
go on holiday|take a vacation;go on a getaway;take a trip
see places|go sightseeing;explore attractions;take in the sights
tourists|holidaymakers;visitors;sightseers
nice place|scenic destination;idyllic spot;picturesque location
travel abroad|travel overseas;venture abroad;go on international trips
hotel|accommodation;lodging;resort
""",
        "food" to """
junk food|processed food;fast food;unhealthy snacks
delicious|mouth-watering;flavoursome;scrumptious
cook|prepare meals;whip up a dish;rustle up a meal
eat too much|overindulge;overeat;binge
restaurant|eatery;dining establishment;bistro
traditional food|local cuisine;regional dishes;authentic fare
""",
        "sports" to """
do sport|play sport;participate in sports;engage in athletic activities
fit|in good shape;athletic;physically fit
win|triumph;emerge victorious;clinch the title
lose|be defeated;suffer a loss;fall short
team|squad;side;line-up
watch matches|follow fixtures;spectate;tune in to games
""",
        "family" to """
parents|caregivers;guardians;mother and father
children|offspring;youngsters;kids
get along|have a close bond;be on good terms;relate well
family is important|family is the cornerstone of society;family ties matter;kinship is vital
grow up|be raised;be brought up;come of age
argue|clash;quarrel;have disagreements
""",
        "transport" to """
cars|private vehicles;automobiles;motor vehicles
public transport|mass transit;public transportation;commuter services
bus|coach;public bus;shuttle
go to work|commute;travel to work;make the daily journey
traffic|congestion;rush hour;gridlock
bike|bicycle;cycle;two-wheeler
""",
        "housing" to """
house|property;dwelling;residence
rent|lease;rent out;let
expensive houses|soaring property prices;unaffordable housing;sky-high rents
small flat|compact apartment;cramped flat;studio
neighbours|residents nearby;the local community;people next door
build more houses|increase housing supply;develop affordable housing;expand residential construction
""",
        "shopping" to """
buy things|make purchases;shop;splash out on
shopping online|e-commerce;online retail;shopping on digital platforms
brands|labels;designer names;trademarks
buy too much|overconsume;shop compulsively;go on spending sprees
shop|retail outlet;store;boutique
discount|bargain;special offer;markdown
""",
        "art" to """
paintings|artworks;canvases;masterpieces
artist|creator;painter;visionary
creative|imaginative;inventive;innovative
art is important|art enriches society;art fosters expression;art is culturally vital
draw|sketch;illustrate;depict
exhibition|gallery show;display;showcase
""",
        "music" to """
songs|tracks;tunes;compositions
singer|vocalist;performer;artist
listen to music|tune in to music;stream music;enjoy music
concert|live performance;gig;show
catchy|memorable;infectious;upbeat
play an instrument|perform on an instrument;master an instrument;take up an instrument
""",
        "science" to """
scientists|researchers;academics;experts
experiment|trial;study;investigation
discover|uncover;make a breakthrough;identify
science is useful|science underpins progress;research drives innovation;science improves lives
test ideas|test hypotheses;validate theories;verify findings
data|evidence;findings;statistics
""",
        "socialmedia" to """
social media is bad|social media can be detrimental;social media fuels comparison;social media harms wellbeing
post photos|share images;upload content;post updates
followers|audience;online following;subscribers
use phone too much|spend excessive screen time;depend on my phone;scroll compulsively
famous online|go viral;become an influencer;gain online fame
chat online|message;connect virtually;interact online
""",
        "climate" to """
weather gets hot|temperatures are rising;global warming accelerates;heatwaves intensify
climate change is bad|climate change poses an existential threat;climate change is catastrophic;the climate crisis is escalating
floods|flooding;rising sea levels;extreme weather events
reduce carbon|cut emissions;lower our carbon footprint;decarbonise
green|eco-friendly;sustainable;environmentally conscious
stop climate change|mitigate climate change;combat the climate crisis;curb global warming
""",
        "energy" to """
electricity|power;energy supply;the grid
solar power|solar energy;photovoltaic power;renewable solar
oil and coal|fossil fuels;hydrocarbons;non-renewable resources
save electricity|reduce power consumption;be energy-efficient;cut energy use
clean energy|renewable energy;green energy;low-carbon power
energy is expensive|energy costs are soaring;energy bills are crippling;power prices are surging
""",
        "wildlife" to """
animals|wildlife;fauna;creatures
plants|flora;vegetation;greenery
animals dying|species extinction;biodiversity loss;dwindling populations
protect animals|conserve wildlife;protect endangered species;establish sanctuaries
forest|woodland;rainforest;jungle
zoo|wildlife park;sanctuary;conservation centre
""",
        "language" to """
learn english|acquire english;master english;become proficient in english
speak well|speak fluently;express myself articulately;communicate effectively
foreign language|second language;additional language;target language
practice|hone my skills;polish my english;practise regularly
grammar mistakes|grammatical errors;inaccuracies;slips in grammar
vocabulary|lexicon;word bank;range of expressions
""",
        "leisure" to """
free time|leisure time;spare time;downtime
hobby|pastime;leisure pursuit;interest
have fun|enjoy myself;have a whale of a time;have a blast
watch movies|go to the cinema;binge-watch films;catch a film
hang out|socialise;spend time with friends;get together
weekend|days off;time off;the weekend break
""",
        "fashion" to """
clothes|garments;attire;outfits
fashionable|trendy;stylish;in vogue
old-fashioned|outdated;dated;out of style
wear|sport;dress in;don
expensive brands|luxury labels;high-end fashion;designer brands
look nice|look stylish;look elegant;look smart
""",
        "government" to """
government should help|the authorities should intervene;the state ought to support;policymakers should act
rules|regulations;legislation;policies
tax|taxation;levy;duties
public services|state services;public amenities;public infrastructure
leaders|policymakers;officials;decision-makers
spend money on|allocate funds to;invest in;channel resources into
""",
        "hometown" to """
my town|my hometown;my local area;where I grew up
small town|close-knit community;quiet town;small settlement
lots of shops|a thriving commercial area;a wide range of amenities;a bustling high street
it has changed|it has undergone a transformation;it has evolved considerably;it has changed beyond recognition
friendly people|welcoming locals;hospitable residents;a warm-hearted community
nothing to do|a lack of amenities;limited entertainment;not much going on
""",
    )

    val entries: List<Entry> by lazy {
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        for ((sectionName, block) in SECTIONS) {
            val topic = sectionName.substringBefore('~')
            val suggestOnly = sectionName.endsWith("~suggest")
            for (line in block.lines()) {
                val t = line.trim()
                if (t.isEmpty() || !t.contains('|')) continue
                val source = TextAnalysis.normalize(t.substringBefore('|').trim())
                val ups = t.substringAfter('|').split(';').map { it.trim() }.filter { it.isNotEmpty() }
                if (ups.isEmpty() || !seen.add("$topic::$source")) continue
                val tokens = source.split(' ')
                val auto = !suggestOnly && (topic == "general" || tokens.size <= 2)
                out += Entry(source, tokens, ups, topic, auto)
            }
        }
        out
    }

    val size: Int get() = entries.size

    val topics: List<String> get() = SECTIONS.map { it.first.substringBefore('~') }.filter { it != "general" }.distinct()

    private val byFirst: Map<String, List<Entry>> by lazy {
        entries.groupBy { it.tokens.first() }.mapValues { (_, v) -> v.sortedByDescending { it.tokens.size } }
    }

    /** All upgrade targets (lower-case token sequences) - used to credit learners who already use them. */
    private val advancedTargets: Map<String, List<List<String>>> by lazy {
        val set = HashSet<List<String>>()
        for (e in entries) for (u in e.upgrades) {
            val toks = TextAnalysis.words(u)
            if (toks.size >= 2 || (toks.size == 1 && toks[0].length >= 7)) set += toks
        }
        set.groupBy { it.first() }.mapValues { (_, v) -> v.sortedByDescending { it.size } }
    }

    /**
     * Finds upgradable B1/B2 items in [text]. Longest match wins; a topic entry beats the general
     * one for the same source; each source is reported once; at most [limit] suggestions.
     */
    fun findUpgrades(text: String, topic: String?, limit: Int = 14): List<VocabularyUpgrade> {
        val tokens = TextAnalysis.tokenize(text)
        val lowers = tokens.map { it.lower }
        val topicKey = topic?.lowercase()
        val found = ArrayList<VocabularyUpgrade>()
        val reported = HashSet<String>()
        var i = 0
        while (i < tokens.size) {
            val candidates = byFirst[lowers[i]]
            var best: Entry? = null
            if (candidates != null) {
                var bestLen = 0
                for (e in candidates) {
                    val n = e.tokens.size
                    if (e.topic != "general" && e.topic != topicKey) continue
                    if (n < bestLen || i + n > tokens.size) continue
                    var ok = true
                    for (k in 0 until n) if (lowers[i + k] != e.tokens[k]) { ok = false; break }
                    if (!ok) continue
                    val better = n > bestLen || (n == bestLen && e.topic == topicKey && best?.topic != topicKey)
                    if (better) { best = e; bestLen = n }
                }
            }
            if (best != null) {
                val n = best.tokens.size
                if (reported.add(best.source)) {
                    val start = tokens[i].start
                    val end = tokens[i + n - 1].end
                    found += VocabularyUpgrade(text.substring(start, end), start, end, best.upgrades, best.topic, best.autoApply)
                }
                i += n
            } else {
                i++
            }
        }
        // Multi-word and topic-specific suggestions first: they are the most actionable.
        return found.sortedWith(compareByDescending<VocabularyUpgrade> { it.original.contains(' ') }
            .thenByDescending { it.topic != "general" }
            .thenBy { it.start })
            .take(limit)
            .sortedBy { it.start }
    }

    /**
     * Sophisticated items (upgrade targets/idioms) the learner already used. Single words count
     * only when the CEFR profile rates them B2+ (or they are long off-list words); phrases count
     * when they contain a B2+ word or span three or more tokens.
     */
    fun advancedItemsUsed(lowerTokens: List<String>, cefr: CefrLookup? = null): List<String> {
        val hits = ArrayList<String>()
        var i = 0
        while (i < lowerTokens.size) {
            val cands = advancedTargets[lowerTokens[i]]
            var matched: List<String>? = null
            if (cands != null) {
                for (c in cands) {
                    if (i + c.size > lowerTokens.size) continue
                    var ok = true
                    for (k in c.indices) if (lowerTokens[i + k] != c[k]) { ok = false; break }
                    if (ok) { matched = c; break }
                }
            }
            if (matched != null) {
                val advanced = if (cefr == null) true else {
                    val levels = matched.map { cefr.levelOf(it) }
                    val anyAdvanced = levels.any { it != null && it.rank >= com.polylingua.edge.domain.model.CefrLevel.B2.rank }
                    if (matched.size == 1) anyAdvanced || (levels[0] == null && matched[0].length >= 9) else anyAdvanced || matched.size >= 3
                }
                if (advanced) hits += matched.joinToString(" ")
                i += matched.size
            } else i++
        }
        return hits
    }

    /** Basic words whose overuse depresses Lexical Resource. */
    val BASIC_OVERUSE: Set<String> = setOf(
        "good", "bad", "very", "thing", "things", "nice", "big", "small", "really", "lot", "lots",
        "stuff", "get", "got", "okay", "ok", "great", "fine", "kind", "sort", "many", "much",
    )
}
