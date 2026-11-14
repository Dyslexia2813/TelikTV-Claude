# Как продолжить проект в OpenCode

OpenCode — консольный AI-агент для кода. При запуске в папке репозитория он сам читает `AGENTS.md`,
поэтому вся нужная информация о проекте подхватится автоматически.

## Первый запуск

1. Установить git и OpenCode (одна из команд, см. https://opencode.ai):
   ```
   curl -fsSL https://opencode.ai/install | bash
   # или
   npm i -g opencode-ai
   ```
2. Скачать репозитории:
   ```
   git clone https://github.com/Dyslexia2813/TelikTV-Claude.git
   git clone https://github.com/Dyslexia2813/TelikIVI.git
   ```
3. Чтобы агент мог пушить и смотреть сборки, войти в GitHub: установить GitHub CLI и выполнить `gh auth login`.
4. Открыть нужный проект и запустить агента:
   ```
   cd TelikTV-Claude
   opencode
   ```
5. Выбрать модель (`/models`) и написать задачу по-русски, например:
   «Прочитай AGENTS.md и docs/KNOWN-ISSUES.md. Сделай тест скорости сегмента в диагностике (v1.16),
   запушь и скажи, когда сборка в Actions будет готова».

Команду `/init` запускать не нужно: `AGENTS.md` уже написан (она бы его перезаписала).

## Как идёт работа

1. Агент меняет код, поднимает версию в `app/build.gradle`, дописывает `docs/HISTORY.md`, делает коммит и push.
2. GitHub Actions собирает APK (5–10 минут). Проверить: вкладка **Actions** на GitHub или `gh run list`.
3. Скачать артефакт `TelikTV-apk-<версия>` (или `gh run download`), поставить APK на приставку.
4. Нажать на пульте **Вверх**, сфотографировать диагностику и рассказать агенту, что видно.

Сборка на своём компьютере необязательна; если хочется — нужны JDK 17 и Android SDK, команда
`./gradlew assembleRelease`, APK появится в `app/build/outputs/apk/release/`.
