// @unocss-include
import { getColorPalette, getRgb } from '@sa/color';
import { DARK_CLASS } from '@/constants/app';
import { localStg } from '@/utils/storage';
import { toggleHtmlClass } from '@/utils/common';
import { $t } from '@/locales';

export function setupLoading() {
  const themeColor = localStg.get('themeColor') || '#646cff';
  const darkMode = localStg.get('darkMode') || false;
  const palette = getColorPalette(themeColor);

  const { r, g, b } = getRgb(themeColor);

  const primaryColor = `--primary-color: ${r} ${g} ${b}`;

  const svgCssVars = Array.from(palette.entries())
    .map(([key, value]) => `--logo-color-${key}: ${value}`)
    .join(';');

  const cssVars = `${primaryColor}; ${svgCssVars}`;

  if (darkMode) {
    toggleHtmlClass(DARK_CLASS).add();
  }

  const loadingClasses = [
    'left-0 top-0',
    'left-0 bottom-0 animate-delay-500',
    'right-0 top-0 animate-delay-1000',
    'right-0 bottom-0 animate-delay-1500'
  ];

  const dot = loadingClasses
    .map(item => {
      return `<div class="absolute w-16px h-16px bg-primary rounded-8px animate-pulse ${item}"></div>`;
    })
    .join('\n');

  const loading = `
<div class="fixed-center flex-col bg-layout" style="${cssVars}">
  <div class="w-128px h-128px flex items-center justify-center">
    ${getLogoSvg()}
  </div>
  <div class="w-56px h-56px my-36px">
    <div class="relative h-full animate-spin">
      ${dot}
    </div>
  </div>
  <h2 class="text-28px font-500 text-primary">${$t('system.title')}</h2>
</div>`;

  const app = document.getElementById('app');

  if (app) {
    app.innerHTML = loading;
  }
}

function getLogoSvg() {
  // 使用与主题色一致的深色
  const primaryRgb = getRgb(localStg.get('themeColor') || '#646cff');
  const primaryColor = `rgb(${primaryRgb.r}, ${primaryRgb.g}, ${primaryRgb.b})`;

  // 简单的 M 字母 Logo SVG
  const logoSvg = `<svg
    width="100%"
    height="100%"
    viewBox="0 0 1024 1024"
    xmlns="http://www.w3.org/2000/svg"
  >
    <path
      d="M58.187 261.514 489.751 506.067C497.23 510.305 506.385 510.305 513.865 506.067L945.428 261.514C962.457 251.865 961.814 227.117 944.308 218.364L512.744 2.582C505.86 -0.86 497.756 -0.86 490.871 2.582L59.307 218.364C41.801 227.117 41.159 251.865 58.187 261.514Z"
      fill="${primaryColor}"
    />
    <path
      d="M477.352 504.93V974.794C477.352 993.514 497.526 1005.293 513.829 996.091L974.164 736.254C981.846 731.918 986.598 723.78 986.598 714.957V398.477C986.598 384.971 975.649 374.022 962.143 374.022 948.636 374.022 937.687 384.971 937.687 398.477V714.957L950.121 693.661 489.787 953.497 526.263 974.794V504.93C526.263 491.423 515.314 480.474 501.808 480.474 488.301 480.474 477.352 491.423 477.352 504.93Z"
      fill="${primaryColor}"
    />
    <path
      d="M514.422 953.843 82.858 694.006 94.699 714.957V254.623C94.699 241.116 83.75 230.167 70.244 230.167 56.738 230.167 45.789 241.116 45.789 254.623V714.957C45.789 723.534 50.282 731.484 57.63 735.908L489.194 995.745C500.764 1002.712 515.792 998.979 522.759 987.408 529.725 975.837 525.993 960.81 514.422 953.843Z"
      fill="${primaryColor}"
    />
    <path
      d="M172.745 448.218 316.6 534.531C328.182 541.48 343.204 537.725 350.152 526.143 357.101 514.562 353.346 499.54 341.764 492.591L197.91 406.278C186.328 399.329 171.306 403.085 164.357 414.666 157.408 426.248 161.164 441.27 172.745 448.218Z"
      fill="${primaryColor}"
    />
  </svg>`;

  return logoSvg;
}
