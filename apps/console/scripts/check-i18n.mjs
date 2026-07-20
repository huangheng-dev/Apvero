import { readFile } from "node:fs/promises";

async function loadLocale(file, exportName) {
  const source = await readFile(new URL(`../${file}`, import.meta.url), "utf8");
  const executable = source.replace(`export const ${exportName} =`, `const ${exportName} =`);
  return Function(`${executable}\nreturn ${exportName};`)();
}

function flatten(value, prefix = "") {
  return Object.entries(value).flatMap(([key, child]) => {
    const path = prefix ? `${prefix}.${key}` : key;
    return child && typeof child === "object" ? flatten(child, path) : [path];
  });
}

const [english, chinese] = await Promise.all([
  loadLocale("src/locales/en.ts", "en"),
  loadLocale("src/locales/zh-CN.ts", "zhCN"),
]);
const englishKeys = flatten(english).sort();
const chineseKeys = flatten(chinese).sort();
const missingChinese = englishKeys.filter((key) => !chineseKeys.includes(key));
const missingEnglish = chineseKeys.filter((key) => !englishKeys.includes(key));

if (missingChinese.length || missingEnglish.length) {
  console.error("English and Simplified Chinese locale keys differ.");
  if (missingChinese.length) console.error(`Missing zh-CN: ${missingChinese.join(", ")}`);
  if (missingEnglish.length) console.error(`Missing en: ${missingEnglish.join(", ")}`);
  process.exit(1);
}

console.log(`i18n coverage OK: ${englishKeys.length} leaf keys in each required locale.`);
