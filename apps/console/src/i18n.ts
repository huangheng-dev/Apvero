import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import { en } from "./locales/en";
import { zhCN } from "./locales/zh-CN";

const stored = localStorage.getItem("apvero.locale");
const language = stored === "zh-CN" ? "zh-CN" : "en";

void i18n.use(initReactI18next).init({
  resources: { en: { translation: en }, "zh-CN": { translation: zhCN } },
  lng: language,
  fallbackLng: "en",
  interpolation: { escapeValue: false },
});

export default i18n;
