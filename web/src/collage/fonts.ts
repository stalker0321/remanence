export const FONT_VERSION = 1

const files: Record<string, string> = {
  'StudySans/400/false': 'nimbus_sans_regular.otf',
  'StudySans/700/false': 'nimbus_sans_bold.otf',
  'StudySerif/400/false': 'nimbus_roman_regular.otf',
  'StudySerif/400/true': 'nimbus_roman_italic.otf',
  'StudySerif/700/false': 'nimbus_roman_bold.otf',
  'StudyMono/400/false': 'nimbus_mono_ps_regular.otf',
  'Narrow/700/false': 'nimbus_sans_narrow_bold.otf',
  'Gothic/400/false': 'urw_gothic_book.otf',
  'Gothic/700/false': 'urw_gothic_demi.otf',
}

export function fontFile(family: string, weight: number, italic: boolean): string | null {
  return files[`${family}/${weight}/${italic}`] ?? null
}
