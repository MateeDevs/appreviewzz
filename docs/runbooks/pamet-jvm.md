# Paměť JVM a rozbor růstu z 3. 11. 2026

## Závěr

Pozorovaný růst RAM + swapu způsobovalo především nevhodné nastavení JVM na sdíleném
hostiteli. Každý proces mohl mít heap 75 % **celého serveru**, kontejnery neměly limity
a Java 21 používala negenerační ZGC. I healthchecky tak postupně hromadily dočasné objekty,
zatímco GC při malém provozu dlouho neběžel. Kernel mezitím odkládal stránky do swapu.

Prudký pokles grafu nebyl důkaz pádu. U velkých historických poklesů chybí události
ukončení příslušných kontejnerů; kernelové logy současného i předchozího bootu neobsahují
OOM zabití za sledované období. Jeden pokles 2. 11. odpovídá restartu **celého hostitele**
v 07:58 UTC. Lokální reprodukce ukázala růst i u healthchecků, po GC přitom zůstalo
jen přibližně 70 MiB živých objektů. Pro tuto příčinu není potřeba předpokládat leak.

Oprava je v [issue #13](https://github.com/MateeDevs/appreviewzz/issues/13).

## Důkazy z běžícího serveru

Měření 3. 11. 2026 kolem 14:33 UTC; historie z interního Promethea za předchozích sedm dní,
vzorkovaná po 30 minutách. Všechny časy v tomto dokumentu jsou UTC.

| Zjištění | Hodnota |
|---|---:|
| Fyzická RAM hostitele | 7,54 GiB |
| Java procesy appreviewzz | 4: API + worker, staging + produkce |
| `HostConfig.Memory` / `MemorySwap` u všech čtyř JVM | 0 / 0, bez limitu |
| `jvm_memory_max_bytes{area="heap",id="ZHeap"}` každého procesu | 6 073 352 192 B = 5,66 GiB |
| Součet povolených heapů | 22,6 GiB, bez režie JVM a ostatních aplikací |
| Nejvyšší vzorkovaný součet RAM + swapu projektu | přibližně 5,35 GiB |
| Restart count / OOM killed nových kontejnerů při měření | 0 / false |

Historická maxima jednotlivých metrik nejsou ze stejného okamžiku a nesčítají se:

| Proces | Nejvyšší RAM working set | Nejvyšší swap |
|---|---:|---:|
| Staging API | 483 MiB | 1 823 MiB |
| Produkční API | 505 MiB | 1 596 MiB |
| Staging worker | 959 MiB | 1 292 MiB |
| Produkční worker | 878 MiB | 1 235 MiB |

Například staging worker 27. 10. mezi vzorky v 21:04 a 21:34 snížil RAM + swap
z přibližně 2 022 na 397 MiB. V Docker journalu není jeho ukončení ani restart.
Historické kontejnery byly při dnešních deployích odstraněny, jejich tehdejší heap
ani GC logy nejsou uchované. Historie proto neumožňuje přiřadit každý jednotlivý pokles
konkrétnímu GC cyklu; kombinace metrik, lifecycle logů a reprodukce ale příčinu podporuje.

PromQL pro rozpad projektu po kontejnerech:

```promql
container_memory_working_set_bytes
  * on(id) group_left(container)
    max by(id, container) (server_monitor_container_info{project="appreviewzz"})
```

Pro swap se první metrika nahradí `container_memory_swap`. Graf projektu sčítá oba
typy paměti přes staging, produkci a jejich databáze; není to heap jedné aplikace.

## Prověřené cesty v kódu

- `applicationDefaultJvmArgs` nastavovalo ZGC a `MaxRAMPercentage=75.0`.
  Dockerfile stejné procento přidával podruhé. Ani jeden Compose neměl limit paměti.
- HTTP klienti v `Components` vznikají jako sdílené lazy instance. V opakovaných
  jobech se nevytváří nový HTTP klient pro každou recenzi.
- Registry metrik je jeden na proces. Ktor 3.6.0 standardně agreguje routy podle
  šablony a nerozlišuje jednotlivé neznámé URL. V běžících procesech bylo při měření
  jen 1–9 HTTP časovačů, v reprodukci dva. Exploze počtu metrik se nepotvrdila.
- Cache tokenů a DEK jsou klíčované credentials nebo datovými klíči. Jejich velikost
  nevysvětluje růst v reprodukci, která žádné credentials ani recenze nepoužívá.
  TTL samo o sobě není zárukou fyzického odstranění všech starých položek.
- Import CSV a historie materializuje dávky v paměti. Může mít vysokou dočasnou
  spotřebu při velkých importech, ale prokázaný růst probíhá i bez něj.
- Limiter a replay guard uklízejí mapy až po dosažení prahu; jejich prahy nejsou
  tvrdým omezením za současného přívalu nových klíčů. To je samostatná kapacitní slabina,
  nikoli vysvětlení měřeného běžného provozu nebo reprodukce bez těchto cest.

Tato analýza nevylučuje malý leak v jiné zátěžové cestě. Pro tvrzení o něm by bylo
potřeba prokázat růst **živého heapu po GC**, případně porovnat heap histogramy.
Samotný graf RAM + swapu takový důkaz neposkytuje.

## Oprava a paměťový rozpočet

| Nastavení | Výchozí hodnota |
|---|---:|
| Minimální heap každé role (`-Xms`) | 64 MiB |
| Maximální heap každé role (`-Xmx`) | 512 MiB |
| Limit kontejneru API / worker | 1 GiB / 1 GiB |
| Povolený swap API / worker | 0 / 0 |
| Collector | G1 |
| Periodická kontrola GC při malém provozu | 60 s |

Polovina limitu zůstává mimo heap: metaspace, code cache, Netty/direct buffery, zásobníky
vláken a další režie. Pevný výchozí heap platí i při přímém spuštění distribuce mimo Docker.
`JAVA_OPTS` v Compose může přepsat `-Xmx` přes `API_HEAP_MAX` / `WORKER_HEAP_MAX`.
Limity kontejnerů mění `API_MEMORY_LIMIT` / `WORKER_MEMORY_LIMIT`. Při zvýšení heapu
zvedni také limit kontejneru; výchozí poměr je 1 : 2.

`memswap_limit` znamená **RAM + swap**, proto se rovná `mem_limit`: například oba `1g`
znamenají 1 GiB RAM a žádný swap. Pouhé nastavení `mem_limit` by swap nevypnulo.
Databáze má jiný profil spotřeby a tato oprava její nastavení nemění.

GC logy jdou na stderr, aby stdout CLI zůstal strojově čitelný. Ukazují spotřebu před
a po sběru. `ExitOnOutOfMemoryError` ukončí proces při skutečném nedostatku heapu;
Compose ho pak obnoví podle `restart: unless-stopped`.

## Lokální ověření

```bash
./gradlew build :server:app:installDist
python3 deploy/diagnostics/memory-smoke.py --compare-old --output /tmp/memory-results.json
```

Test vytvoří vlastní Docker síť a Postgres, spustí skutečné API i worker nad JRE 21,
pro každou roli provede třikrát 10 000 HTTP požadavků na live/ready a počká 75 sekund
na uvolnění dočasných alokací. Nepřipojuje se ke storům, AI ani kanálům. Potřebuje
Docker s cgroups v2; kontejnery a síť po sobě odstraní. Při chybě se test ukončí nenulově.

S `--compare-old` nejdřív spustí stejné procesy se starým ZGC a heapem 5,66 GiB.
Obě varianty mají při tomto kontrolovaném srovnání stejný limit kontejneru 1 GiB bez swapu;
na původním serveru tento limit chyběl. Test tedy srovnává chování GC, nikoli přesnou
repliku produkčního swapování. `memory.current` zahrnuje také souborovou cache;
výstup obsahuje zvlášť working set a anonymní paměť + shmem.

Výsledek ověření 3. 11. 2026 (nejvyšší vzorkované hodnoty, celkem 120 000 požadavků
přes obě role a obě varianty):

| Metrika | Původní JVM: API / worker | Opravená JVM: API / worker |
|---|---:|---:|
| `memory.current`, maximum vzorků | 930 / 881 MiB | 376 / 349 MiB |
| Working set, maximum vzorků | 906 / 880 MiB | 345 / 349 MiB |
| Heap committed po 75 s klidu | 608 / 600 MiB | 64 / 64 MiB |
| Heap used po 75 s klidu | 220 / 182 MiB | 45 / 20 MiB |
| Restarty / OOM | 0 / 0 | 0 / 0 |

Původní ZGC po sběrech hlásil přibližně 70 MiB živých dat, přesto heap zůstával
commitovaný kolem 600 MiB. Opravená konfigurace po zátěži držela 64 MiB commitovaného
heapu. To podporuje závěr, že hlavní růst tvořily dočasné alokace a jejich režie.

Celý Gradle build při opravě prošel: 780 testů, 779 úspěšných a jeden přeskočený.
Ověřena byla samostatná kopie revize s opravou, aby se do výsledku nemíchaly souběžné změny.

## Diagnostika v provozu

```bash
docker inspect --format '{{.RestartCount}} {{.State.OOMKilled}} {{.HostConfig.Memory}} {{.HostConfig.MemorySwap}}' <kontejner>
docker exec <kontejner> cat /sys/fs/cgroup/memory.events /sys/fs/cgroup/memory.swap.current
docker exec <kontejner> curl -fsS http://127.0.0.1:8081/metrics
```

Po nasazení očekávej `Memory` i `MemorySwap` 1 073 741 824, cgroup `memory.swap.max=0`
a heap max 512 MiB (u G1 použij maximum kladných hodnot jednotlivých poolů, ne součet
včetně `-1`, což znamená neurčené maximum). Management port 8081 se nevystavuje veřejně.

Při dalším růstu porovnej heap used/committed/max, GC logy, swap, uptime a změny kontejnerů.
Pokles paměti se stejným uptime je uvolnění paměti; nový proces nebo OOM záznam je jiný případ.
Trvale rostoucí heap **po sběrech** vyžaduje další profilování. Heap dump obsahuje citlivá
data aplikace, proto se nepořizuje ani nepublikuje automaticky.

Push opravy do `epic/v2` vydává staging. Produkce používá vlastní větev `production`
a přebírá opravu až při jejím povýšení podle [runbooku vydání](nasazeni-do-produkce.md).

## Zdroje k chování runtime

- [Oracle: nastavení G1](https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-garbage-collector-tuning.html)
- [Oracle: ZGC a vracení paměti OS](https://docs.oracle.com/en/java/javase/21/gctuning/z-garbage-collector2.html)
- [Docker Compose: paměť a swap](https://docs.docker.com/reference/compose-file/services/#memswap_limit)
