# ThunderHack — Injector + Panel Admina

Dwie aplikacje desktopowe dla klienta **ThunderHack Recode** (Minecraft Fabric):

| Plik | Dla kogo | Co robi |
|------|----------|---------|
| **`Admin.bat`** → `ThunderAdmin.jar` | dla Ciebie (sprzedawca) | generowanie UID-ów, nadawanie rang, wypuszczanie aktualizacji. Hasło: **`2137`** |
| **`Watch.bat`** → `ThunderWatch.jar` | dla Ciebie (sprzedawca) | **kto teraz gra na twoim kliencie** — nicki na żywo, serwer, wersja, IP, czas sesji. Hasło: **`2137`** |
| **`Run.bat`** → `ThunderInjector.jar` | dla kupującego | logowanie UID-em, GUI z gwiazdą i paskiem, injekcja do `.minecraft/mods` |

## ThunderWatch — podgląd użytkowników

1. Odpal **`Watch.vbs`** (katalog główny) albo **`Watch.bat`**, hasło **`2137`**.
2. Zakładka **MOD / SERWER**: przeciągnij `build/libs/thunderhack-*.jar` na pole (albo **WYBIERZ PLIK**),
   ustaw port i kliknij **WŁĄCZ SERWER**.
3. Panel pokazuje link do pobrania moda oraz adres, który gracz wkleja do
   **`config/thunderhack/watch.txt`** (plik tworzy się sam przy pierwszym uruchomieniu klienta).
4. Zakładka **UŻYTKOWNICY**: każdy gracz z tym adresem melduje się co 20 s i widzisz go na żywo —
   nick, wersja, UID, serwer na którym gra, IP, długość sesji. Klik = kopiuj nick, prawy klik = menu.

> Gracze spoza twojej sieci LAN trafią do panelu tylko, jeśli przekierujesz port na routerze
> (albo odpalisz `ThunderWatch.jar` na VPS-ie — to zwykły `java -jar`).
> Pusty / zakomentowany `watch.txt` = klient nie wysyła nic.

## Build
Dwuklik na **`Build.bat`** (wymaga JDK z `javac` + `jar` w PATH — masz Javę 25).
Tworzy `ThunderInjector.jar` i `ThunderAdmin.jar`.

## Workflow sprzedaży
1. Odpalasz panel (`Panel-Admina.vbs` w katalogu głównym lub `Admin.bat`), hasło **`2137`**.
2. Zakładka **LICENCJE / UID**: wybierasz rangę (**CUSTOMER / DEV / TESTER**),
   wpisujesz etykietę (np. nick kupującego) → **GENERUJ UID**.
   - UID-y idą **po kolei**: 1, 2, 3, …
   - UID ląduje w schowku (Ctrl+V).
   - Na **pulpicie** powstaje plik **`ThunderHack-UID-<n>.vbs`** — to gotowy produkt
     dla kupującego: **klik 2x = injector od razu zalogowany tym UID, bez terminala**.
3. **Prawy klik na UID** na liście → menu:
   - **Zbanuj UID / Odbanuj UID** — zbanowany UID nie zaloguje się w injectorze.
   - **Pobierz UID** — ponownie zapisuje plik `.vbs` na pulpit.
4. Zakładka **AKTUALIZACJE**: wersja + kanał + notatki → **WYPUSC AKTUALIZACJE**.
5. Wysyłasz kupującemu plik **`ThunderHack-UID-<n>.vbs`** z pulpitu (+ folder `injector`).

## Co widzi kupujący
1. Odpala **`Run.bat`** / klika `ThunderInjector.jar`.
2. Wpisuje swój **UID** → **ZALOGUJ** (zły UID = pole się trzęsie).
3. Otwiera się GUI: **kręcąca się gwiazda**, na dole **pasek ładowania**,
   a w **lewym dolnym rogu** badge z **buildem (GUI)** i **rangą** (kolor zależny od rangi).
   Klawisz **P** rozwija profil (zamaskowany UID, kanał).
4. **INJECT** → kopiuje `build/libs/thunderhack-*.jar` do `%APPDATA%\.minecraft\mods`.

## Rangi
- **CUSTOMER** — teal (zwykły kupujący)
- **DEV** — fiolet
- **TESTER** — pomarańcz

## Pliki danych (`./data`)
- `licenses.txt` — `uid|rank|label|createdMillis`
- `release.txt` — `version / channel / notes / time`

Injector i Admin czytają ten sam folder `data` obok `.jar`.

## Uwagi
- **Magazyn jest LOKALNY (offline).** Każdy, kto ma `ThunderInjector.jar`, może
  podejrzeć/edytować `data/licenses.txt`. Prawdziwa ochrona licencji wymaga
  serwera z API weryfikującym UID online — tu masz działający szkielet logiki
  (rangi, UID, wydania), który łatwo przepiąć na backend.
- Etykieta **„Fabric 1.21.11"** to napis w GUI. Sam mod w repo jest pod
  Minecraft **1.21** (`gradle.properties`). Realny port na inną wersję MC
  (yarn mappings, fabric-api, mixiny) to osobne zadanie.
