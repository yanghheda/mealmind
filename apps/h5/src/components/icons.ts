/**
 * 统一图标库（Lucide 风格，内联 SVG）—— 严禁 emoji 当图标。
 * 移植自 prototype/icons.js，stroke 1.8、currentColor。
 */
export type IconDefinition = string | { fill: true; svg: string }

export const ICONS: Record<string, IconDefinition> = {
  house: '<path d="M3 10.2 12 3l9 7.2"/><path d="M5.2 9.6V21h13.6V9.6"/><path d="M9.8 21v-5.6h4.4V21"/>',
  'message-circle': '<path d="M7.9 20A9 9 0 1 0 4.1 16L2.2 22l5.7-2z"/>',
  sparkles:
    '<path d="M12 3.2l1.7 5a2 2 0 0 0 1.2 1.2l5 1.7-5 1.7a2 2 0 0 0-1.2 1.2l-1.7 5-1.7-5a2 2 0 0 0-1.2-1.2l-5-1.7 5-1.7a2 2 0 0 0 1.2-1.2l1.7-5z"/><path d="M19 2.8v3.4"/><path d="M20.7 4.5h-3.4"/>',
  'shopping-cart':
    '<circle cx="9" cy="20" r="1.3"/><circle cx="18" cy="20" r="1.3"/><path d="M2.6 3h2.2l2.3 11.1a1.8 1.8 0 0 0 1.8 1.4h8.4a1.8 1.8 0 0 0 1.8-1.4L20.7 7H5.4"/>',
  user: '<circle cx="12" cy="7.6" r="3.6"/><path d="M5 20.2a7 7 0 0 1 14 0"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m20.2 20.2-3.6-3.6"/>',
  'map-pin':
    '<path d="M20 10.4c0 5.4-8 10.6-8 10.6s-8-5.2-8-10.6a8 8 0 0 1 16 0z"/><circle cx="12" cy="10.2" r="2.6"/>',
  scan: '<path d="M3 7.5V5.4A2.4 2.4 0 0 1 5.4 3h2.1"/><path d="M16.5 3h2.1A2.4 2.4 0 0 1 21 5.4v2.1"/><path d="M21 16.5v2.1a2.4 2.4 0 0 1-2.4 2.4h-2.1"/><path d="M7.5 21H5.4A2.4 2.4 0 0 1 3 18.6v-2.1"/><path d="M7 12h10"/>',
  'chevron-down': '<path d="m6.5 9.5 5.5 5.5 5.5-5.5"/>',
  'chevron-right': '<path d="m9.5 6.5 5.5 5.5-5.5 5.5"/>',
  'chevron-up': '<path d="m6.5 14.5 5.5-5.5 5.5 5.5"/>',
  'arrow-left': '<path d="m12 19-7-7 7-7"/><path d="M19 12H5"/>',
  share: '<path d="M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8"/><path d="m16 6-4-4-4 4"/><path d="M12 2v13"/>',
  'more-horizontal': { fill: true, svg: '<circle cx="5" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="19" cy="12" r="1.6"/>' },
  'sliders-horizontal':
    '<path d="M20.5 6.5h-6"/><path d="M9.5 6.5h-6"/><path d="M20.5 12h-9"/><path d="M7 12H3.5"/><path d="M20.5 17.5h-3.5"/><path d="M12.5 17.5h-9"/><circle cx="12" cy="6.5" r="2"/><circle cx="9.5" cy="12" r="2"/><circle cx="14.5" cy="17.5" r="2"/>',
  percent:
    '<circle cx="12" cy="12" r="9"/><path d="m15 9-6 6"/><circle cx="9.6" cy="9.6" r="1.2"/><circle cx="14.4" cy="14.4" r="1.2"/>',
  utensils:
    '<path d="M6.5 2.5v6a2 2 0 0 0 4 0v-6"/><path d="M8.5 2.5v19"/><path d="M17.5 2.5c-1.7 2.2-2.5 4.7-2.5 7.3 0 1.8.8 3.4 2.5 4.4v7.3"/>',
  zap: '<path d="M13 2.5 4.5 13.5H11L10 21.5 18.5 10.5H12l1-8z"/>',
  bike: '<circle cx="5.6" cy="17.6" r="3.4"/><circle cx="18.4" cy="17.6" r="3.4"/><path d="M15 6.2h3l2 5.4"/><path d="M6 17.6 9.4 7h3.2l4.4 10.6"/><path d="M12.6 7l-.5-2h-2"/>',
  star: { fill: true, svg: '<path d="M12 2.9l2.7 5.4 6 .9-4.3 4.2 1 6-5.4-2.9-5.4 2.9 1-6L3.3 9.2l6-.9L12 2.9z"/>' },
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7.2V12l3.2 1.9"/>',
  gift: '<rect x="3.2" y="8" width="17.6" height="4" rx="1"/><path d="M5 12v8.4h14V12"/><path d="M12 8v12.4"/><path d="M12 8S10.8 3.2 8 3.2a2.3 2.3 0 0 0 0 4.8"/><path d="M12 8s1.2-4.8 4-4.8a2.3 2.3 0 0 1 0 4.8"/>',
  plus: '<path d="M12 5.5v13"/><path d="M5.5 12h13"/>',
  minus: '<path d="M5.5 12h13"/>',
  x: '<path d="m6 6 12 12"/><path d="M18 6 6 18"/>',
  'trash-2':
    '<path d="M4 7h16"/><path d="M9.5 7V5.2A1.2 1.2 0 0 1 10.7 4h2.6a1.2 1.2 0 0 1 1.2 1.2V7"/><path d="m6.5 7 .9 12.4A1.6 1.6 0 0 0 9 21h6a1.6 1.6 0 0 0 1.6-1.6L17.5 7"/><path d="M10 11v6"/><path d="M14 11v6"/>',
  'circle-check': '<circle cx="12" cy="12" r="9"/><path d="m8.4 12.3 2.5 2.5 4.7-5"/>',
  'shopping-bag':
    '<path d="M6.2 7.5h11.6L17 20.6a1.6 1.6 0 0 1-1.6 1.4H8.6A1.6 1.6 0 0 1 7 20.6L6.2 7.5z"/><path d="M9 7.5V6a3 3 0 0 1 6 0v1.5"/>',
  pill: '<path d="M10.6 20.4a5.1 5.1 0 0 1-7.2-7.2l6.8-6.8a5.1 5.1 0 0 1 7.2 7.2l-6.8 6.8z"/><path d="m7.8 9.8 6.4 6.4"/>',
  sprout: '<path d="M7 21h10"/><path d="M12 21v-7.5"/><path d="M12 13.5c0-4-2.5-7-7-7 0 4 3 7 7 7z"/><path d="M12 13.5c0-4 2.5-7 7-7 0 4-3 7-7 7z"/>',
  flower:
    '<circle cx="12" cy="12" r="1.7"/><path d="M12 8.2c0-2.6 1.2-4.2 2.9-3.8 1.6.4 2 2.7.4 4.5"/><path d="M15.8 12c2.6 0 4.2 1.2 3.8 2.9-.4 1.6-2.7 2-4.5.4"/><path d="M12 15.8c0 2.6-1.2 4.2-2.9 3.8-1.6-.4-2-2.7-.4-4.5"/><path d="M8.2 12c-2.6 0-4.2-1.2-3.8-2.9.4-1.6 2.7-2 4.5-.4"/>',
  coffee: '<path d="M17 9h1.2a2.8 2.8 0 0 1 0 5.6H17"/><path d="M3.5 9h13.5v5a5 5 0 0 1-5 5H8.5a5 5 0 0 1-5-5V9z"/><path d="M7 2.8v2.6"/><path d="M11 2.8v2.6"/>',
  citrus: '<circle cx="12" cy="13.2" r="6.4"/><path d="M12 6.8c0-2.2 1.6-3.8 3.8-3.8"/><path d="M9 13.2h6"/><path d="M12 10.2v6"/>',
  coins: '<circle cx="12" cy="12" r="8.6"/><path d="M9.6 9.2h4.8"/><path d="M12 9.2v5.6"/><path d="M9.6 12.2h4.8"/>',
  flame:
    '<path d="M12 21.6c3.9 0 6.8-2.6 6.8-6.3 0-3.4-2.4-5.3-3.4-8.3-.8-2.3-1-5.3-3.4-6.8-.6 2.9-1.9 4.4-3.4 6.3-1.5 1.9-3.4 3.9-3.4 8.8 0 3.7 2.9 6.3 6.8 6.3z"/>',
  refresh: '<path d="M20 11a8 8 0 0 0-14.6-3.9"/><path d="M3.5 3.5V8H8"/><path d="M4 13a8 8 0 0 0 14.6 3.9"/><path d="M20.5 20.5V16H16"/>',
  send: '<path d="M21.5 2.5 14 21.2l-3.6-7.6-7.6-3.6L21.5 2.5z"/><path d="M21.5 2.5 10.4 13.6"/>',
  'circle-alert': '<circle cx="12" cy="12" r="9"/><path d="M12 7.6v5.2"/><path d="M12 16.4h.01"/>',
}
