export type SupportedLocale = "en" | "zh-CN";

export function toSupportedLocale(value: string | null | undefined): SupportedLocale {
  return value === "zh-CN" ? "zh-CN" : "en";
}

export async function applyLocale(
  locale: SupportedLocale,
  changeLanguage: (next: SupportedLocale) => Promise<unknown>,
  storage: Pick<Storage, "setItem"> = window.localStorage,
) {
  storage.setItem("apvero.locale", locale);
  await changeLanguage(locale);
}
