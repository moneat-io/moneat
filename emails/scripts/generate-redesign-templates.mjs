// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

// Moneat transactional email redesign - single generator for the whole set.
// House aesthetic: "smooth color, crisp structure" &mdash; cool slate neutrals, ONE cyan
// accent, the app's deep cyan-navy masthead, status as a fixed language, hairline /
// band separation instead of boxes+shadows, monospace for machine data.
// Run: node build.mjs   &rarr; writes standalone, email-client-safe HTML into ./out
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const C = {
  appBg:'#f7f8fa', surface:'#ffffff', subtle:'#eef0f4', inset:'#f1f2f6',
  fg:'#161922', fgStrong:'#0e1016', fgMuted:'#6b7280', fgSubtle:'#9aa1ae',
  border:'#d8dce3', borderMuted:'#e4e7ec', borderStrong:'#c2c8d2',
  link:'#0369a1', accent:'#0ea5e9',
  navy:'#082f49', navyMuted:'#8fcde8',
  dangerFg:'#ad1a1f', dangerBg:'#fdecec', dangerBorder:'#f3b3b5', dangerSolid:'#cf2126',
  warnFg:'#7a5500', warnBg:'#fdf6e3', warnBorder:'#f5d98a', warnSolid:'#e0a100',
  successFg:'#0b7558', successBg:'#e9f7f1', successBorder:'#a7e3cd', successSolid:'#0e8c6b',
  infoFg:'#2049a1', infoBg:'#eaf1fc', infoBorder:'#b6cdf3', infoSolid:'#3b74dd',
  scaleGood:'#18a07a', scaleWarn:'#e0a100', scaleBad:'#e5484d',
  vizBg:'#0f1620', vizBg2:'#16202c', vizFg:'#d7e1ec', vizMuted:'#8a99a9', vizBorder:'#21303f',
  btn:'#161922',
};
const SANS = "'Inter',-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif";
const MONO = "'JetBrains Mono',ui-monospace,'SF Mono',Menlo,Consolas,monospace";
// Real Moneat brand mark (cyan ring + peak). Production hosts this PNG at a stable URL.
const LOGO_MARK = 'https://moneat.io/email/logo-mark.png';
const STRIP = { danger:C.dangerSolid, warning:C.warnSolid, success:C.successSolid, info:C.infoSolid, accent:C.accent };

const doc = ({ title, preheader='', body }) => `<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="x-apple-disable-message-reformatting">
<meta name="color-scheme" content="light"><meta name="supported-color-schemes" content="light">
<title>${title}</title>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
<style>
  body{margin:0;padding:0;-webkit-font-smoothing:antialiased;}
  a{text-decoration:none;}
  img{border:0;outline:none;-ms-interpolation-mode:bicubic;}
  table{border-collapse:collapse;mso-table-lspace:0;mso-table-rspace:0;}
  @media only screen and (max-width:600px){
    .shell{width:100% !important;border-radius:0 !important;border-left:0 !important;border-right:0 !important;}
    .px{padding-left:18px !important;padding-right:18px !important;}
  }
</style>
</head>
<body style="margin:0;padding:0;background:${C.appBg};">
<div style="display:none;max-height:0;overflow:hidden;opacity:0;mso-hide:all;font-size:1px;line-height:1px;color:${C.appBg};">${preheader}</div>
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:${C.appBg};">
<tr><td align="center" style="padding:28px 12px;">
<!--[if mso]><table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0"><tr><td><![endif]-->
<table role="presentation" class="shell" align="center" width="100%" cellpadding="0" cellspacing="0" border="0" style="width:100%;max-width:600px;background:${C.surface};border:1px solid ${C.border};border-radius:12px;overflow:hidden;box-shadow:0 1px 3px rgba(16,18,26,0.09);">
${body}
</table>
<table role="presentation" class="shell" align="center" width="100%" cellpadding="0" cellspacing="0" style="width:100%;max-width:600px;"><tr><td align="center" style="padding:16px 28px 4px;">
<p style="margin:0;font:400 11px/1.5 ${SANS};color:${C.fgSubtle};">Sent by Moneat &middot; You can adjust which events email you from notification preferences.</p>
</td></tr></table>
<!--[if mso]></td></tr></table><![endif]-->
</td></tr></table>
</body></html>`;

const strip = (kind) => `<tr><td style="height:3px;line-height:3px;font-size:0;background:${STRIP[kind]};">&nbsp;</td></tr>`;

const masthead = (context='') => `
<tr><td class="px" style="padding:15px 28px;background:${C.navy};">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>
<td style="vertical-align:middle;">
  <table role="presentation" cellpadding="0" cellspacing="0"><tr>
  <td style="vertical-align:middle;"><img src="${LOGO_MARK}" width="24" height="24" alt="Moneat" style="display:block;border:0;outline:none;width:24px;height:24px;"></td>
  <td style="vertical-align:middle;padding-left:9px;"><span style="font:700 17px/24px ${SANS};color:#ffffff;letter-spacing:-0.01em;">moneat</span></td>
  </tr></table>
</td>
<td align="right" style="vertical-align:middle;">${context?`<span style="font:600 11px/1 ${MONO};letter-spacing:0.04em;color:${C.navyMuted};text-transform:uppercase;">${context}</span>`:''}</td>
</tr></table>
</td></tr>`;

const footer = (stream='notifications') => `
<tr><td class="px" style="padding:22px 28px 26px;background:${C.appBg};border-top:1px solid ${C.borderMuted};">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr><td align="center">
  <div style="margin-bottom:9px;">
    <img src="${LOGO_MARK}" width="16" height="16" alt="" style="display:inline-block;border:0;outline:none;width:16px;height:16px;vertical-align:middle;"><span style="vertical-align:middle;font:700 13px/16px ${SANS};color:${C.fg};padding-left:7px;">moneat</span>
  </div>
  <p style="margin:0 0 9px;font:400 12px/1.5 ${SANS};color:${C.fgMuted};">You're receiving this because ${stream} are enabled for your account.</p>
  <p style="margin:0 0 12px;font:400 12px/1.5 ${SANS};"><a href="#" style="color:${C.link};">Manage preferences</a><span style="color:${C.borderStrong};padding:0 7px;">&middot;</span><a href="#" style="color:${C.fgMuted};">Unsubscribe</a></p>
  <p style="margin:0;font:400 11px/1.4 ${SANS};color:${C.fgSubtle};">&copy; 2026 Moneat &middot; 1235 East Blvd, Ste E PMB 2045, Charlotte, NC 28203, USA</p>
</td></tr></table>
</td></tr>`;

// section band; alternate surface / appBg, hairline top to crisp the seam
const sec = (inner,{bg=C.surface,pt=20,pb=20,border=true}={}) =>
  `<tr><td class="px" style="padding:${pt}px 28px ${pb}px;background:${bg};${border?`border-top:1px solid ${C.borderMuted};`:''}">${inner}</td></tr>`;

const label = (t) => `<div style="font:600 11px/1 ${MONO};letter-spacing:0.08em;text-transform:uppercase;color:${C.fgMuted};">${t}</div>`;
const h1 = (t) => `<h1 style="margin:11px 0 0;font:600 19px/1.32 ${SANS};color:${C.fgStrong};letter-spacing:-0.01em;">${t}</h1>`;
const lead = (t) => `<p style="margin:12px 0 0;font:400 14px/1.6 ${SANS};color:${C.fgMuted};">${t}</p>`;
const dot = (c) => `<span style="display:inline-block;width:8px;height:8px;border-radius:999px;background:${c};vertical-align:middle;"></span>`;

const BADGE = {
  danger:[C.dangerBg,C.dangerFg,C.dangerBorder], warning:[C.warnBg,C.warnFg,C.warnBorder],
  success:[C.successBg,C.successFg,C.successBorder], info:[C.infoBg,C.infoFg,C.infoBorder],
  neutral:[C.subtle,'#353a45',C.border], accent:['#f0f9ff',C.link,'#bae6fd'],
};
const badge = (text,kind='neutral',caps=false) => { const [bg,fg,bd]=BADGE[kind];
  return `<span style="display:inline-block;padding:3px 8px;border-radius:6px;background:${bg};color:${fg};border:1px solid ${bd};font:600 ${caps?'10':'11'}px/1.3 ${SANS};${caps?'letter-spacing:0.06em;text-transform:uppercase;':''}vertical-align:middle;">${text}</span>`; };

const button = (lbl,href='#') => `<table role="presentation" cellpadding="0" cellspacing="0"><tr><td bgcolor="${C.btn}" style="border-radius:8px;"><a href="${href}" style="display:inline-block;padding:13px 22px;font:600 14px/1 ${SANS};color:#ffffff;border-radius:8px;">${lbl}</a></td></tr></table>`;

const linksRow = (links) => `<table role="presentation" cellpadding="0" cellspacing="0"><tr>${links.map((l,i)=>`${i?`<td style="padding:0 11px;color:${C.borderStrong};font-size:12px;line-height:1;">&middot;</td>`:''}<td><a href="#" style="font:500 13px/1 ${SANS};color:${C.link};">${l}</a></td>`).join('')}</tr></table>`;

const statRow = (items,bg=C.surface) => { const w=(100/items.length).toFixed(4);
  return `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:${bg};"><tr>${items.map((it,i)=>`
    <td width="${w}%" style="padding:0 14px;${i?`border-left:1px solid ${C.borderMuted};`:''}vertical-align:top;">
      <div style="font:600 21px/1.1 ${MONO};color:${C.fgStrong};letter-spacing:-0.01em;">${it.value}${it.delta?` <span style="font:600 11px/1 ${SANS};color:${it.deltaKind==='bad'?C.dangerSolid:it.deltaKind==='good'?C.successSolid:C.fgMuted};white-space:nowrap;">${it.delta}</span>`:''}</div>
      <div style="margin-top:5px;font:500 10px/1.3 ${SANS};color:${C.fgMuted};letter-spacing:0.05em;text-transform:uppercase;">${it.label}</div>
    </td>`).join('')}</tr></table>`; };

const kv = (rows) => `<table role="presentation" width="100%" cellpadding="0" cellspacing="0">${rows.map((r,i)=>`<tr>
  <td style="padding:9px 0;${i?`border-top:1px solid ${C.borderMuted};`:''}font:500 12px/1.4 ${SANS};color:${C.fgMuted};vertical-align:top;width:40%;">${r.k}</td>
  <td style="padding:9px 0;${i?`border-top:1px solid ${C.borderMuted};`:''}text-align:right;font:${r.mono?`500 12px/1.5 ${MONO}`:`500 13px/1.5 ${SANS}`};color:${C.fg};vertical-align:top;">${r.v}</td>
</tr>`).join('')}</table>`;

const scaleColor = (p) => p>=85?C.scaleBad:p>=70?C.scaleWarn:C.scaleGood;

// breach bar-gauge: fill to value with a dark threshold tick
const gauge = ({value,max,threshold,valueText,thresholdText}) => {
  const pv=Math.max(0,Math.min(100,value/max*100)), pt=Math.max(0,Math.min(100,threshold/max*100));
  const breached=value>=threshold, fill=breached?C.scaleBad:C.scaleGood;
  const a=Math.min(pv,pt), mid=Math.max(0,pv-pt), rest=Math.max(0,100-a-mid-0.7);
  return `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-radius:999px;overflow:hidden;background:${C.subtle};"><tr>
    <td style="height:9px;width:${a}%;background:${fill};line-height:9px;font-size:0;">&nbsp;</td>
    <td style="height:9px;width:0.7%;background:${C.fgStrong};line-height:9px;font-size:0;">&nbsp;</td>
    <td style="height:9px;width:${mid}%;background:${fill};line-height:9px;font-size:0;">&nbsp;</td>
    <td style="height:9px;width:${rest}%;background:${C.subtle};line-height:9px;font-size:0;">&nbsp;</td>
  </tr></table>
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin-top:7px;"><tr>
    <td style="font:600 12px/1 ${MONO};color:${breached?C.dangerFg:C.fg};">${valueText}</td>
    <td align="right" style="font:500 11px/1 ${SANS};color:${C.fgMuted};">threshold ${thresholdText}</td>
  </tr></table>`;
};

const miniGauge = ({label:l,pct}) => { const c=scaleColor(pct); return `
  <div style="font:500 10px/1.3 ${SANS};color:${C.fgMuted};letter-spacing:0.05em;text-transform:uppercase;">${l}</div>
  <div style="font:600 16px/1.2 ${MONO};color:${C.fg};margin:4px 0 7px;">${pct}<span style="font-size:11px;color:${C.fgMuted};">%</span></div>
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-radius:999px;overflow:hidden;background:${C.subtle};"><tr>
    <td style="height:6px;width:${pct}%;background:${c};line-height:6px;font-size:0;">&nbsp;</td>
    <td style="height:6px;width:${100-pct}%;background:${C.subtle};line-height:6px;font-size:0;">&nbsp;</td>
  </tr></table>`; };

const statCell = ({value,label:l,sub}) => `
  <div style="font:500 10px/1.3 ${SANS};color:${C.fgMuted};letter-spacing:0.05em;text-transform:uppercase;">${l}</div>
  <div style="font:600 16px/1.2 ${MONO};color:${C.fg};margin:4px 0 0;">${value}</div>
  ${sub?`<div style="margin-top:4px;font:500 11px/1.2 ${SANS};color:${C.fgMuted};">${sub}</div>`:''}`;

const grid4 = (cells) => `<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>${cells.map((c,i)=>`<td width="25%" style="padding:0 12px;${i?`border-left:1px solid ${C.borderMuted};`:''}vertical-align:top;">${c}</td>`).join('')}</tr></table>`;

const sparkline = (vals,spikeFrom=null,H=46) => { const max=Math.max(...vals);
  return `<table role="presentation" cellpadding="0" cellspacing="0" style="height:${H}px;"><tr style="vertical-align:bottom;">${vals.map((v,i)=>{
    const h=Math.max(2,Math.round(v/max*H)); const c=(spikeFrom!=null&&i>=spikeFrom)?C.dangerSolid:C.accent;
    return `<td style="vertical-align:bottom;padding:0 1px;"><div style="width:9px;height:${h}px;background:${c};border-radius:2px 2px 0 0;"></div></td>`;
  }).join('')}</tr></table>`; };

const tags = (items) => items.map(t=>`<span style="display:inline-block;margin:0 6px 7px 0;padding:3px 8px;border-radius:6px;background:${C.subtle};border:1px solid ${C.border};font:500 11px/1.5 ${MONO};color:${C.fg};white-space:nowrap;"><span style="color:${C.fgMuted};">${t.k}</span> ${t.v}</span>`).join('');

const eyebrow = (left,right) => `<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr><td>${label(left)}</td><td align="right"><span style="font:500 11px/1 ${MONO};color:${C.fgSubtle};">${right}</span></td></tr></table>`;

const meta = (cells) => `<table role="presentation" cellpadding="0" cellspacing="0" style="margin-top:13px;"><tr>${cells.map((c,i)=>`<td style="${i?'padding-left:9px;':''}vertical-align:middle;">${c}</td>`).join('')}</tr></table>`;

const iconTile = (glyph) => `<table role="presentation" cellpadding="0" cellspacing="0" align="center" style="margin:0 auto;"><tr><td style="width:56px;height:56px;border-radius:14px;background:#f0f9ff;border:1px solid #bae6fd;text-align:center;"><span style="font:400 26px/56px ${SANS};color:${C.link};">${glyph}</span></td></tr></table>`;

const fallbackUrl = (url) => sec(`${label('Or paste this link')}
  <div style="margin-top:9px;padding:11px 13px;background:${C.inset};border:1px solid ${C.borderMuted};border-radius:8px;font:500 12px/1.5 ${MONO};color:${C.link};word-break:break-all;">${url}</div>`,{bg:C.surface});

const infoRows = (rows) => `<table role="presentation" width="100%" cellpadding="0" cellspacing="0">${rows.map((r,i)=>`<tr>
  <td style="padding:11px 0;${i?`border-top:1px solid ${C.borderMuted};`:''}width:18px;vertical-align:top;">${dot(C.accent)}</td>
  <td style="padding:11px 0 11px 10px;${i?`border-top:1px solid ${C.borderMuted};`:''}font:400 13px/1.5 ${SANS};color:${C.fg};">${r}</td>
</tr>`).join('')}</table>`;

const stackTrace = (file,lines) => `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-radius:8px;overflow:hidden;background:${C.vizBg};border:1px solid ${C.vizBorder};">
  <tr><td style="padding:9px 14px;background:${C.vizBg2};border-bottom:1px solid ${C.vizBorder};"><span style="font:500 11px/1 ${MONO};color:${C.vizMuted};letter-spacing:0.04em;">${file} &middot; stack trace</span></td></tr>
  <tr><td style="padding:13px 14px;">${lines.map(L=>{
    if(L.head) return `<div style="font:500 12px/1.7 ${MONO};color:#f3b3b5;">${L.head}</div>`;
    if(L.app) return `<div style="font:500 12px/1.7 ${MONO};color:${C.vizFg};background:rgba(56,189,248,0.12);border-left:2px solid ${C.accent};padding:1px 0 1px 10px;margin:2px 0 2px -2px;">${L.app}${L.in?` <span style="color:${C.accent};font-size:10px;">in&nbsp;app</span>`:''}</div>`;
    return `<div style="font:500 12px/1.7 ${MONO};color:${C.vizMuted};padding-left:10px;">${L.v}</div>`;
  }).join('')}</td></tr></table>`;

/* ============================== EMAILS ============================== */
const emails = {};

// 1 &mdash; Issue / error alert (the rich hero) -------------------------------------
emails['01-issue-alert'] = doc({
  title:'[checkout-api] New issue &middot; TypeError in CartController.computeTotals',
  preheader:'Error &middot; 1,284 events &middot; 312 users affected &middot; first seen 14 min ago in release v2.4.1',
  body: strip('danger') + masthead('Production &middot; us-east-1')
  + sec(`${eyebrow('New issue','14 min ago')}
      ${h1("TypeError: Cannot read properties of undefined (reading 'total')")}
      ${meta([dot(C.dangerSolid)+' '+badge('Error','danger',true), badge('checkout-api','accent'), badge('production','neutral')])}`,
      {border:false, pb:18})
  + sec(`${label('Service health &middot; checkout-api')}
      <div style="height:13px;"></div>
      ${statRow([
        {value:'4.7%',label:'Error rate',delta:'&uarr; 3.9pt',deltaKind:'bad'},
        {value:'842 ms',label:'p95 latency',delta:'&uarr; 210',deltaKind:'bad'},
        {value:'1.2k/m',label:'Throughput',delta:'&darr; 8%',deltaKind:'neutral'},
      ], C.appBg)}`, {bg:C.appBg})
  + sec(`${statRow([
        {value:'1,284',label:'Occurrences'},
        {value:'312',label:'Users affected'},
        {value:'14m ago',label:'First seen'},
        {value:'8s ago',label:'Last seen'},
      ])}
      <div style="height:20px;"></div>
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>
        <td>${label('Events &middot; last 24h')}</td>
        <td align="right"><span style="font:600 12px/1 ${MONO};color:${C.fg};">1,284 events</span></td>
      </tr></table>
      <div style="height:10px;"></div>
      ${sparkline([4,5,4,6,5,7,6,8,7,6,9,8,7,10,9,11,10,13,14,16,21,30,40,46],20)}`)
  + sec(`${label('Where')}
      <div style="margin-top:9px;font:600 13px/1.5 ${MONO};color:${C.fgStrong};">CartController.computeTotals</div>
      <div style="margin-top:2px;font:500 12px/1.5 ${MONO};color:${C.fgMuted};">src/controllers/cart.ts:148:24</div>
      <div style="height:20px;"></div>
      ${label('Likely cause &middot; deploy')}
      <div style="margin-top:10px;">
        <table role="presentation" cellpadding="0" cellspacing="0"><tr>
          <td style="vertical-align:middle;">${badge('release v2.4.1','accent')}</td>
          <td style="vertical-align:middle;padding-left:10px;font:400 13px/1.5 ${SANS};color:${C.fg};">Deployed <strong style="color:${C.fgStrong};">18 min</strong> before first event &middot; 7 commits &middot; Priya&nbsp;Nair</td>
        </tr></table>
      </div>`, {bg:C.appBg})
  + sec(`${label('Stack trace')}
      <div style="height:10px;"></div>
      ${stackTrace('cart.ts',[
        {head:"TypeError: Cannot read properties of undefined (reading 'total')"},
        {app:'at CartController.computeTotals (src/controllers/cart.ts:148:24)',in:true},
        {app:'at CheckoutService.createOrder (src/services/checkout.ts:92:18)'},
        {app:'at POST /api/checkout (src/routes/checkout.ts:31:5)'},
        {v:'at Layer.handle (node_modules/express/lib/router/layer.js:95:5)'},
        {v:'at next (node_modules/express/lib/router/route.js:144:13)'},
      ])}`)
  + sec(`${label('Context')}
      <div style="height:11px;"></div>
      ${tags([
        {k:'runtime',v:'node@20.11'}, {k:'host',v:'checkout-api-7f9c2'}, {k:'region',v:'us-east-1a'},
        {k:'http.route',v:'POST /api/checkout'}, {k:'release',v:'v2.4.1'}, {k:'browser',v:'&mdash;'},
      ])}
      <div style="margin-top:8px;font:400 12px/1.5 ${SANS};color:${C.fgMuted};">Code owner <span style="color:${C.fg};font-weight:600;">@checkout-team</span> &middot; suggested from CODEOWNERS</div>`)
  + sec(`${button('Investigate issue &rarr;')}
      <div style="height:16px;"></div>
      ${linksRow(['View trace','Search logs','Assign','Mute issue'])}`, {bg:C.appBg})
  + footer('issue alerts'),
});

// 2 &mdash; Host / infrastructure alert --------------------------------------------
emails['02-host-alert'] = doc({
  title:'[checkout-api-7f9c2] Critical &middot; CPU 92.5% over 90% threshold',
  preheader:'Host alert &middot; CPU 92.5% (threshold 90%) sustained 5m 12s &middot; 8 vCPU &middot; us-east-1a',
  body: strip('danger') + masthead('Production &middot; us-east-1')
  + sec(`${eyebrow('Host alert','3 min ago')}
      ${h1('High CPU load on checkout-api-7f9c2')}
      ${meta([dot(C.dangerSolid)+' '+badge('Critical','danger',true), badge('CPU > 90% for 5m','neutral'), badge('production','neutral')])}`,
      {border:false, pb:18})
  + sec(`${label('CPU usage')}
      <div style="margin:10px 0 14px;"><span style="font:600 32px/1 ${MONO};color:${C.dangerFg};letter-spacing:-0.02em;">92.5%</span> <span style="font:500 13px/1 ${SANS};color:${C.fgMuted};">&uarr; from 41% baseline &middot; sustained 5m 12s</span></div>
      ${gauge({value:92.5,max:100,threshold:90,valueText:'92.5% now',thresholdText:'90%'})}`, {bg:C.appBg})
  + sec(`${label('Host vitals')}
      <div style="height:14px;"></div>
      ${grid4([
        miniGauge({label:'CPU',pct:92}),
        miniGauge({label:'Memory',pct:78}),
        miniGauge({label:'Disk',pct:64}),
        statCell({value:'7.82',label:'Load 1m',sub:'8 vCPU'}),
      ])}`)
  + sec(`${label('Host')}
      <div style="height:8px;"></div>
      ${kv([
        {k:'Hostname',v:'checkout-api-7f9c2',mono:true},
        {k:'Private IP',v:'10.12.4.8',mono:true},
        {k:'Region / AZ',v:'us-east-1a',mono:true},
        {k:'Instance',v:'c6i.2xlarge &middot; 8 vCPU &middot; 16 GiB'},
        {k:'OS',v:'Ubuntu 22.04 LTS'},
        {k:'Agent',v:'moneat-agent v1.42.0',mono:true},
        {k:'Uptime',v:'23d 4h 11m',mono:true},
      ])}`, {bg:C.appBg})
  + sec(`${label('Top processes by CPU')}
      <div style="height:11px;"></div>
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
        <tr><td style="padding:0 0 8px;font:600 10px/1 ${SANS};color:${C.fgMuted};letter-spacing:0.05em;text-transform:uppercase;">Process</td><td align="right" style="padding:0 0 8px;font:600 10px/1 ${SANS};color:${C.fgMuted};letter-spacing:0.05em;text-transform:uppercase;">CPU</td><td align="right" style="padding:0 0 8px;font:600 10px/1 ${SANS};color:${C.fgMuted};letter-spacing:0.05em;text-transform:uppercase;">Mem</td></tr>
        ${[['node server.js','61.2%','2.1 GiB',C.dangerFg],['clickhouse-server','14.8%','3.4 GiB',C.fg],['otel-collector','6.1%','412 MiB',C.fg]].map((r,i)=>`<tr>
          <td style="padding:9px 0;border-top:1px solid ${C.borderMuted};font:500 12px/1.4 ${MONO};color:${C.fg};">${r[0]}</td>
          <td align="right" style="padding:9px 0;border-top:1px solid ${C.borderMuted};font:600 12px/1.4 ${MONO};color:${r[3]};">${r[1]}</td>
          <td align="right" style="padding:9px 0;border-top:1px solid ${C.borderMuted};font:500 12px/1.4 ${MONO};color:${C.fgMuted};">${r[2]}</td>
        </tr>`).join('')}
      </table>
      <div style="height:20px;"></div>
      ${label('CPU &middot; last hour')}
      <div style="height:10px;"></div>
      ${sparkline([38,40,39,42,41,44,46,45,48,52,55,58,61,64,68,72,76,80,84,88,90,92,93,92],19)}`)
  + sec(`${button('View host &rarr;')}
      <div style="height:16px;"></div>
      ${linksRow(['Open dashboard','Silence 1h','Runbook'])}`, {bg:C.appBg})
  + footer('infrastructure alerts'),
});

// 3 &mdash; Monitor / dashboard metric alert ---------------------------------------
emails['03-monitor-alert'] = doc({
  title:'[Production Overview] Alert &middot; 5xx error rate 15.2% over 10%',
  preheader:'Monitor triggered &middot; 5xx error rate 15.2% (threshold 10%) over 5m avg',
  body: strip('danger') + masthead('Production')
  + sec(`${eyebrow('Monitor alert','1 min ago')}
      ${h1('5xx error rate above 10% on Production Overview')}
      ${meta([dot(C.dangerSolid)+' '+badge('Alert','danger',true), badge('Metric monitor','neutral')])}`,
      {border:false, pb:18})
  + sec(`${label('5xx error rate &middot; 5m average')}
      <div style="margin:10px 0 14px;"><span style="font:600 32px/1 ${MONO};color:${C.dangerFg};letter-spacing:-0.02em;">15.2%</span> <span style="font:500 13px/1 ${SANS};color:${C.fgMuted};">&uarr; from 2.1% baseline</span></div>
      ${gauge({value:15.2,max:30,threshold:10,valueText:'15.2% now',thresholdText:'10%'})}`, {bg:C.appBg})
  + sec(`${label('Monitor')}
      <div style="height:8px;"></div>
      ${kv([
        {k:'Dashboard',v:'Production Overview'},
        {k:'Widget',v:'HTTP 5xx error rate'},
        {k:'Condition',v:'avg over 5m > 10%',mono:true},
        {k:'Evaluated',v:'every 1 min',mono:true},
        {k:'Triggered',v:'16:30:12 UTC',mono:true},
        {k:'Scope',v:'env:production service:checkout-api',mono:true},
      ])}`)
  + sec(`${label('Error rate &middot; last hour')}
      <div style="height:10px;"></div>
      ${sparkline([2,2,3,2,3,2,3,4,3,4,5,4,6,7,6,8,9,8,10,11,13,14,15,15],18)}
      <div style="margin-top:9px;font:500 11px/1 ${SANS};color:${C.fgMuted};">Red bars indicate samples in breach of the 10% threshold.</div>`, {bg:C.appBg})
  + sec(`${button('View monitor &rarr;')}
      <div style="height:16px;"></div>
      ${linksRow(['Open dashboard','Silence','Edit monitor'])}`)
  + footer('monitor alerts'),
});

// 4 &mdash; Resolved / recovery -----------------------------------------------------
emails['04-resolved'] = doc({
  title:'[checkout-api-7f9c2] Resolved &middot; CPU recovered after 12m 04s',
  preheader:'Recovered &middot; CPU back to 38.1% (peak 92.5%) &middot; alert open 12m 04s',
  body: strip('success') + masthead('Production &middot; us-east-1')
  + sec(`${eyebrow('Resolved','just now')}
      ${h1('CPU load on checkout-api-7f9c2 has recovered')}
      ${meta([dot(C.successSolid)+' '+badge('Resolved','success',true), badge('Auto-resolved','neutral')])}`,
      {border:false, pb:18})
  + sec(`${statRow([
        {value:'12m 04s',label:'Alert duration'},
        {value:'92.5%',label:'Peak value',deltaKind:'bad'},
        {value:'38.1%',label:'Now',delta:'normal',deltaKind:'good'},
      ], C.appBg)}`, {bg:C.appBg})
  + sec(`${label('Timeline')}
      <div style="height:8px;"></div>
      ${kv([
        {k:'Monitor',v:'CPU > 90% for 5m'},
        {k:'Triggered',v:'16:30:12 UTC',mono:true},
        {k:'Recovered',v:'16:42:16 UTC',mono:true},
        {k:'Acknowledged by',v:'&mdash;'},
      ])}
      <div style="height:16px;"></div>
      <p style="margin:0;font:400 13px/1.6 ${SANS};color:${C.fgMuted};">The metric returned to normal levels and the alert closed automatically. No further action is needed.</p>`)
  + sec(`${button('View incident history &rarr;')}
      <div style="height:16px;"></div>
      ${linksRow(['Open dashboard','Mute monitor'])}`, {bg:C.appBg})
  + footer('infrastructure alerts'),
});

// 5 &mdash; Weekly digest -----------------------------------------------------------
emails['05-weekly-digest'] = doc({
  title:'Your week in review &middot; Jun 9 &ndash; Jun 15',
  preheader:'1.84M events &middot; 23 new issues (&darr;12%) &middot; 1,204 users affected across 4 services',
  body: strip('accent') + masthead('Jun 9 &ndash; Jun 15')
  + sec(`${label('Weekly summary')}
      ${h1('Your week in review')}
      <p style="margin:8px 0 0;font:400 13px/1.5 ${SANS};color:${C.fgMuted};">Jun 9 &ndash; Jun 15 &middot; all services &middot; organization Acme Corp</p>`,
      {border:false})
  + sec(`${statRow([
        {value:'1.84M',label:'Events',delta:'&uarr; 6%',deltaKind:'neutral'},
        {value:'23',label:'New issues',delta:'&darr; 12%',deltaKind:'good'},
        {value:'1,204',label:'Users affected',delta:'&uarr; 3%',deltaKind:'neutral'},
      ], C.appBg)}`, {bg:C.appBg})
  + sec(`${label('Top issues this week')}
      <div style="height:12px;"></div>
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0">${[
        ['danger',"TypeError: Cannot read properties of undefined",'checkout-api','1,284'],
        ['warning','Slow query on /api/reports (p95 3.4s)','reports-api','842'],
        ['danger','Timeout calling payments provider','checkout-api','517'],
      ].map((r,i)=>`<tr>
        <td style="padding:11px 0;${i?`border-top:1px solid ${C.borderMuted};`:''}width:14px;vertical-align:top;"><div style="padding-top:4px;">${dot(r[0]==='danger'?C.dangerSolid:C.warnSolid)}</div></td>
        <td style="padding:11px 0 11px 10px;${i?`border-top:1px solid ${C.borderMuted};`:''}">
          <div style="font:600 13px/1.4 ${SANS};color:${C.fgStrong};">${r[1]}</div>
          <div style="margin-top:3px;font:500 11px/1 ${MONO};color:${C.fgMuted};">${r[2]}</div>
        </td>
        <td align="right" style="padding:11px 0;${i?`border-top:1px solid ${C.borderMuted};`:''}vertical-align:top;"><span style="font:600 13px/1.4 ${MONO};color:${C.fg};">${r[3]}</span><div style="font:500 10px/1.3 ${SANS};color:${C.fgMuted};text-transform:uppercase;letter-spacing:0.05em;">events</div></td>
      </tr>`).join('')}</table>`)
  + sec(`${label('By service')}
      <div style="height:12px;"></div>
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0">
        <tr><td style="padding:0 0 9px;font:600 10px/1 ${SANS};color:${C.fgMuted};letter-spacing:0.05em;text-transform:uppercase;">Service</td><td style="padding:0 12px 9px;font:600 10px/1 ${SANS};color:${C.fgMuted};letter-spacing:0.05em;text-transform:uppercase;">Error rate</td><td align="right" style="padding:0 0 9px;font:600 10px/1 ${SANS};color:${C.fgMuted};letter-spacing:0.05em;text-transform:uppercase;">Events</td></tr>
        ${[['checkout-api',88,'612K','&uarr;'],['reports-api',34,'410K','&uarr;'],['web-frontend',12,'520K','&darr;'],['auth-service',4,'298K','&darr;']].map((r,i)=>`<tr>
          <td style="padding:10px 0;border-top:1px solid ${C.borderMuted};font:500 12px/1.4 ${MONO};color:${C.fg};white-space:nowrap;">${r[0]}</td>
          <td style="padding:10px 12px;border-top:1px solid ${C.borderMuted};vertical-align:middle;">
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-radius:999px;overflow:hidden;background:${C.subtle};"><tr><td style="height:6px;width:${r[1]}%;background:${scaleColor(r[1])};line-height:6px;font-size:0;">&nbsp;</td><td style="height:6px;width:${100-r[1]}%;background:${C.subtle};line-height:6px;font-size:0;">&nbsp;</td></tr></table>
          </td>
          <td align="right" style="padding:10px 0;border-top:1px solid ${C.borderMuted};font:500 12px/1.4 ${MONO};color:${C.fgMuted};">${r[2]} <span style="color:${r[3]==='&uarr;'?C.dangerSolid:C.successSolid};">${r[3]}</span></td>
        </tr>`).join('')}
      </table>`, {bg:C.appBg})
  + sec(`${button('Open dashboard &rarr;')}
      <div style="height:16px;"></div>
      ${linksRow(['Customize digest','Change frequency'])}`)
  + footer('weekly digests'),
});

// 6 &mdash; Billing threshold alert -------------------------------------------------
emails['06-billing-threshold'] = doc({
  title:'Billing &middot; You have used 92% of included log volume',
  preheader:'Logs 46.2 / 50 GB used (92%) &middot; Team plan &middot; period Jun 1 &ndash; Jun 30',
  body: strip('warning') + masthead('Billing')
  + sec(`${eyebrow('Billing alert','')}
      ${h1('You have used 92% of your included log volume')}
      ${meta([dot(C.warnSolid)+' '+badge('Usage threshold','warning',true), badge('Team plan','neutral')])}
      ${lead('At the current rate you are on track to exceed the included volume before the period ends. Overage is billed at $0.40 / GB.')}`,
      {border:false})
  + sec(`${label('Billable usage &middot; Jun 1 &ndash; Jun 30')}
      <div style="height:14px;"></div>
      ${[['Logs','46.2 / 50 GB',92],['Spans','128M / 200M',64],['Metrics','1.20M / 2.00M series',60],['Profiles','9.8 / 25 GB',39]].map((r,i)=>`
        <div style="${i?`border-top:1px solid ${C.borderMuted};padding-top:13px;margin-top:13px;`:''}">
          <table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>
            <td style="font:600 13px/1.4 ${SANS};color:${C.fgStrong};">${r[0]}</td>
            <td align="right" style="font:500 12px/1.4 ${MONO};color:${C.fg};">${r[1]} <span style="color:${scaleColor(r[2])};font-weight:600;">${r[2]}%</span></td>
          </tr></table>
          <div style="height:8px;"></div>
          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-radius:999px;overflow:hidden;background:${C.subtle};"><tr><td style="height:7px;width:${r[2]}%;background:${scaleColor(r[2])};line-height:7px;font-size:0;">&nbsp;</td><td style="height:7px;width:${100-r[2]}%;background:${C.subtle};line-height:7px;font-size:0;">&nbsp;</td></tr></table>
        </div>`).join('')}`, {bg:C.appBg})
  + sec(`${kv([
        {k:'Plan',v:'Team &middot; $0.40 / GB logs overage'},
        {k:'Billing period',v:'Jun 1 &ndash; Jun 30',mono:true},
        {k:'Projected overage',v:'&asymp; $6.40',mono:true},
      ])}`)
  + sec(`${button('Review usage &rarr;')}
      <div style="height:16px;"></div>
      ${linksRow(['Manage plan','Set usage alerts'])}`, {bg:C.appBg})
  + footer('billing notifications'),
});

// 7 &mdash; Billing usage insights --------------------------------------------------
emails['07-billing-insights'] = doc({
  title:'Usage insights &middot; Span ingestion up 38% this period',
  preheader:'Span ingestion up 38%, driven by checkout-api &middot; all usage within your plan',
  body: strip('accent') + masthead('Billing')
  + sec(`${eyebrow('Usage insights','')}
      ${h1('Span ingestion is up 38% this period')}
      ${lead('checkout-api drove most of the increase after the v2.4 rollout. You are still comfortably within your Team plan &mdash; no action needed.')}`,
      {border:false})
  + sec(`${label('Billable usage &middot; Jun 1 &ndash; Jun 30')}
      <div style="height:14px;"></div>
      ${[['Logs','31.4 / 50 GB',63],['Spans','176M / 200M',88],['Metrics','1.10M / 2.00M series',55],['Profiles','7.2 / 25 GB',29]].map((r,i)=>`
        <div style="${i?`border-top:1px solid ${C.borderMuted};padding-top:13px;margin-top:13px;`:''}">
          <table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>
            <td style="font:600 13px/1.4 ${SANS};color:${C.fgStrong};">${r[0]}</td>
            <td align="right" style="font:500 12px/1.4 ${MONO};color:${C.fg};">${r[1]} <span style="color:${scaleColor(r[2])};font-weight:600;">${r[2]}%</span></td>
          </tr></table>
          <div style="height:8px;"></div>
          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-radius:999px;overflow:hidden;background:${C.subtle};"><tr><td style="height:7px;width:${r[2]}%;background:${scaleColor(r[2])};line-height:7px;font-size:0;">&nbsp;</td><td style="height:7px;width:${100-r[2]}%;background:${C.subtle};line-height:7px;font-size:0;">&nbsp;</td></tr></table>
        </div>`).join('')}`, {bg:C.appBg})
  + sec(`${label('Top driver')}
      <div style="height:10px;"></div>
      ${kv([
        {k:'Service',v:'checkout-api'},
        {k:'Spans this period',v:'112M &uarr; 38%',mono:true},
        {k:'Plan',v:'Team &middot; within included usage'},
        {k:'Estimated overage',v:'$0.00',mono:true},
      ])}`)
  + sec(`${button('Open usage insights &rarr;')}
      <div style="height:16px;"></div>
      ${linksRow(['Manage plan','Set usage alerts'])}`, {bg:C.appBg})
  + footer('billing notifications'),
});

// 8 &mdash; Verify email ------------------------------------------------------------
emails['08-verify-email'] = doc({
  title:'Verify your email for Moneat',
  preheader:'Confirm your email address to finish setting up your Moneat account.',
  body: strip('accent') + masthead('Account')
  + sec(`<div style="text-align:center;">${iconTile('&#10003;')}
      <h1 style="margin:18px 0 0;font:600 21px/1.3 ${SANS};color:${C.fgStrong};letter-spacing:-0.01em;">Verify your email</h1>
      <p style="margin:11px auto 0;max-width:420px;font:400 14px/1.6 ${SANS};color:${C.fgMuted};">Hi Alex &mdash; thanks for signing up. Confirm your email address to finish setting up your Moneat account and start sending telemetry.</p>
      <div style="height:22px;"></div>
      <table role="presentation" cellpadding="0" cellspacing="0" align="center" style="margin:0 auto;"><tr><td>${button('Verify email address')}</td></tr></table></div>`,
      {border:false, pt:30})
  + sec(infoRows([
      'This link expires in <strong style="color:'+C.fgStrong+'">24 hours</strong>.',
      "If you didn't create a Moneat account, you can safely ignore this email.",
    ]), {bg:C.appBg})
  + fallbackUrl('https://app.moneat.io/verify?token=8f3a1c9e2b7d4f60a5c1e9d8')
  + footer('account notifications'),
});

// 9 &mdash; Reset password ----------------------------------------------------------
emails['09-reset-password'] = doc({
  title:'Reset your Moneat password',
  preheader:'Use the button below to choose a new password. This link expires in 1 hour.',
  body: strip('accent') + masthead('Account')
  + sec(`<div style="text-align:center;">${iconTile('&#8634;')}
      <h1 style="margin:18px 0 0;font:600 21px/1.3 ${SANS};color:${C.fgStrong};letter-spacing:-0.01em;">Reset your password</h1>
      <p style="margin:11px auto 0;max-width:420px;font:400 14px/1.6 ${SANS};color:${C.fgMuted};">Hi Alex &mdash; we received a request to reset the password on your Moneat account. Choose a new one below.</p>
      <div style="height:22px;"></div>
      <table role="presentation" cellpadding="0" cellspacing="0" align="center" style="margin:0 auto;"><tr><td>${button('Reset password')}</td></tr></table></div>`,
      {border:false, pt:30})
  + sec(infoRows([
      'This link expires in <strong style="color:'+C.fgStrong+'">1 hour</strong>.',
      "If you didn't request this, ignore this email &mdash; your password stays the same.",
    ]), {bg:C.appBg})
  + fallbackUrl('https://app.moneat.io/reset-password?token=a5c1e9d88f3a1c9e2b7d4f60')
  + footer('account notifications'),
});

// 10 &mdash; Org invitation ---------------------------------------------------------
emails['10-org-invitation'] = doc({
  title:'You have been invited to join Acme Corp on Moneat',
  preheader:'Sarah Chen invited you to join Acme Corp on Moneat as an Admin.',
  body: strip('accent') + masthead('Account')
  + sec(`<div style="text-align:center;">${iconTile('+')}
      <h1 style="margin:18px 0 0;font:600 21px/1.3 ${SANS};color:${C.fgStrong};letter-spacing:-0.01em;">You're invited to join Acme&nbsp;Corp</h1>
      <p style="margin:11px auto 0;max-width:440px;font:400 14px/1.6 ${SANS};color:${C.fgMuted};"><strong style="color:${C.fgStrong};">Sarah Chen</strong> invited you to collaborate on monitoring, alerts, and dashboards in Moneat.</p>
      <div style="height:22px;"></div>
      <table role="presentation" cellpadding="0" cellspacing="0" align="center" style="margin:0 auto;"><tr><td>${button('Accept invitation')}</td></tr></table></div>`,
      {border:false, pt:30})
  + sec(`${kv([
        {k:'Organization',v:'Acme Corp'},
        {k:'Role',v:badge('Admin','accent')},
        {k:'Invited by',v:'Sarah Chen &middot; sarah@acme.com',mono:false},
      ])}`, {bg:C.appBg})
  + sec(infoRows([
      'This invitation expires in <strong style="color:'+C.fgStrong+'">7 days</strong>.',
      "No account yet? You'll create one when you accept &mdash; it takes about a minute.",
    ]))
  + fallbackUrl('https://app.moneat.io/accept-invite?token=abc123def456ghi789')
  + footer('account notifications'),
});

const re = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const replaceMany = (html, replacements) =>
  replacements.reduce((current, [from, to]) => current.split(from).join(to), html);

const setPrimaryHref = (html, label, href) =>
  html.replace(new RegExp(`href="#"(?= style="[^"]*">${re(label)})`), `href="${href}"`);

const setActionHrefs = (html, links) =>
  links.reduce((current, [label, href]) => setPrimaryHref(current, label, href), html);

const replaceSection = (html, startNeedle, endNeedle, replacement) => {
  const start = html.indexOf(startNeedle);
  const end = html.indexOf(endNeedle, start + startNeedle.length);
  if (start === -1 || end === -1) return html;
  return html.slice(0, start) + replacement + html.slice(end);
};

const withCommonFooterTokens = (html) =>
  html
    .replaceAll('href="#" style="color:#0369a1;">Manage preferences</a>', 'href="{{ settingsUrl }}" style="color:#0369a1;">Manage preferences</a>')
    .replaceAll('href="#" style="color:#6b7280;">Unsubscribe</a>', 'href="{{ unsubscribeUrl }}" style="color:#6b7280;">Unsubscribe</a>')
    .replaceAll('&copy; 2026 Moneat', '&copy; {{ year }} Moneat');

const cleanupGenerated = (html) =>
  html
    .replace(
      /<div style="">[\s\S]*?<\/td><\/tr><tr><td class="px" style="padding:20px 28px 20px;background:#ffffff;border-top:1px solid #e4e7ec;">/,
      'BILLING_ROWS_PLACEHOLDER</td></tr><tr><td class="px" style="padding:20px 28px 20px;background:#ffffff;border-top:1px solid #e4e7ec;">',
    )
    .replaceAll(
      '<div style="font:600 11px/1 \'JetBrains Mono\',ui-monospace,\'SF Mono\',Menlo,Consolas,monospace;letter-spacing:0.08em;text-transform:uppercase;color:#6b7280;">Context</div><div style="font:600 11px/1 \'JetBrains Mono\',ui-monospace,\'SF Mono\',Menlo,Consolas,monospace;letter-spacing:0.08em;text-transform:uppercase;color:#6b7280;">Context</div>',
      '<div style="font:600 11px/1 \'JetBrains Mono\',ui-monospace,\'SF Mono\',Menlo,Consolas,monospace;letter-spacing:0.08em;text-transform:uppercase;color:#6b7280;">Context</div>',
    )
    .replaceAll(
      '<div style="font:600 11px/1 \'JetBrains Mono\',ui-monospace,\'SF Mono\',Menlo,Consolas,monospace;letter-spacing:0.08em;text-transform:uppercase;color:#6b7280;">By service</div><div style="font:600 11px/1 \'JetBrains Mono\',ui-monospace,\'SF Mono\',Menlo,Consolas,monospace;letter-spacing:0.08em;text-transform:uppercase;color:#6b7280;">By service</div>',
      '<div style="font:600 11px/1 \'JetBrains Mono\',ui-monospace,\'SF Mono\',Menlo,Consolas,monospace;letter-spacing:0.08em;text-transform:uppercase;color:#6b7280;">By service</div>',
    )
    .replaceAll(
      '<div style="font:600 11px/1 \'JetBrains Mono\',ui-monospace,\'SF Mono\',Menlo,Consolas,monospace;letter-spacing:0.08em;text-transform:uppercase;color:#6b7280;">{{ cpuLabel }} &middot; last hour</div><div style="font:600 11px/1 \'JetBrains Mono\',ui-monospace,\'SF Mono\',Menlo,Consolas,monospace;letter-spacing:0.08em;text-transform:uppercase;color:#6b7280;">{{ cpuLabel }} &middot; last hour</div>',
      '<div style="font:600 11px/1 \'JetBrains Mono\',ui-monospace,\'SF Mono\',Menlo,Consolas,monospace;letter-spacing:0.08em;text-transform:uppercase;color:#6b7280;">{{ cpuLabel }} &middot; last hour</div>',
    )
    .replaceAll(
      '<table role="presentation" cellpadding="0" cellspacing="0"><tr><td bgcolor="#161922"</td></tr><tr><td class="px" style="padding:20px 28px 20px;background:#ffffff;border-top:1px solid #e4e7ec;"><table role="presentation" cellpadding="0" cellspacing="0"><tr><td bgcolor="#161922"',
      '<table role="presentation" cellpadding="0" cellspacing="0"><tr><td bgcolor="#161922"',
    )
    .replaceAll(
      '<table role="presentation" width="100%"</td></tr><tr><td class="px" style="padding:20px 28px 20px;background:#ffffff;border-top:1px solid #e4e7ec;"><table role="presentation" width="100%"',
      '<table role="presentation" width="100%"',
    )
    .replace(/[ \t]+$/gm, '');

const parameterizeIssueAlert = () => {
  let html = withCommonFooterTokens(emails['01-issue-alert']);
  html = replaceMany(html, [
    ['[checkout-api] New issue &middot; TypeError in CartController.computeTotals', '[{{ projectName }}] New issue &middot; {{ issueTitle }}'],
    ['Error &middot; 1,284 events &middot; 312 users affected &middot; first seen 14 min ago in release v2.4.1', '{{ issueLevel }} &middot; {{ issueCount }} events &middot; {{ usersAffected }} users affected &middot; first seen {{ firstSeen }}'],
    ['Production &middot; us-east-1', '{{ environment }}'],
    ['TypeError: Cannot read properties of undefined (reading \'total\')', '{{ issueTitle }}'],
    ['14 min ago', '{{ timestamp }}'],
    ['>Error</span>', '>{{ issueLevel }}</span>'],
    ['checkout-api', '{{ projectName }}'],
    ['production', '{{ environment }}'],
    ['4.7%', '{{ errorRate }}'],
    ['&uarr; 3.9pt', '{{ errorRateDelta }}'],
    ['842 ms', '{{ p95Latency }}'],
    ['&uarr; 210', '{{ p95LatencyDelta }}'],
    ['1.2k/m', '{{ throughput }}'],
    ['&darr; 8%', '{{ throughputDelta }}'],
    ['1,284 events', '{{ issueCount }} events'],
    ['1,284', '{{ issueCount }}'],
    ['312', '{{ usersAffected }}'],
    ['14m ago', '{{ firstSeen }}'],
    ['8s ago', '{{ lastSeen }}'],
    ['CartController.computeTotals', '{{ issueFunction }}'],
    ['src/controllers/cart.ts:148:24', '{{ issueLocation }}'],
    ['release v2.4.1', 'release {{ release }}'],
    ['Deployed <strong style="color:#0e1016;">18 min</strong> before first event &middot; 7 commits &middot; Priya&nbsp;Nair', '{{ deploySummary }}'],
    ['@checkout-team', '{{ codeOwner }}'],
  ]);
  html = html.replace(
    sparkline([4,5,4,6,5,7,6,8,7,6,9,8,7,10,9,11,10,13,14,16,21,30,40,46],20),
    'ISSUE_SPARKLINE_PLACEHOLDER',
  );
  const stackStart = '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-radius:8px;overflow:hidden;background:#0f1620;border:1px solid #21303f;">';
  const contextStart = '<div style="font:600 11px/1 \'JetBrains Mono\',ui-monospace,\'SF Mono\',Menlo,Consolas,monospace;letter-spacing:0.08em;text-transform:uppercase;color:#6b7280;">Context</div>';
  html = replaceSection(html, stackStart, contextStart, 'STACK_FRAMES_PLACEHOLDER</td></tr><tr><td class="px" style="padding:20px 28px 20px;background:#ffffff;border-top:1px solid #e4e7ec;">' + contextStart);
  html = html.replace(
    tags([
      {k:'runtime',v:'node@20.11'}, {k:'host',v:'{{ projectName }}-7f9c2'}, {k:'region',v:'us-east-1a'},
      {k:'http.route',v:'POST /api/checkout'}, {k:'release',v:'v2.4.1'}, {k:'browser',v:'&mdash;'},
    ]),
    'ISSUE_CONTEXT_TAGS_PLACEHOLDER',
  );
  return setActionHrefs(
    setPrimaryHref(html, 'Investigate issue &rarr;', '{{ issueUrl }}'),
    [
      ['View trace', '{{ issueUrl }}'],
      ['Search logs', '{{ issueUrl }}'],
      ['Assign', '{{ issueUrl }}'],
      ['Mute issue', '{{ issueUrl }}'],
    ],
  );
};

const parameterizeHostAlert = () => {
  let html = withCommonFooterTokens(emails['02-host-alert']);
  html = replaceMany(html, [
    ['[checkout-api-7f9c2] Critical &middot; CPU 92.5% over 90% threshold', '[{{ hostName }}] {{ severity }} &middot; {{ metricName }} {{ currentValue }} over {{ threshold }} threshold'],
    ['Host alert &middot; CPU 92.5% (threshold 90%) sustained 5m 12s &middot; 8 vCPU &middot; us-east-1a', 'Host alert &middot; {{ metricName }} {{ currentValue }} (threshold {{ threshold }}) sustained {{ sustainedDuration }} &middot; {{ vcpu }} vCPU &middot; {{ region }}'],
    ['Production &middot; us-east-1', '{{ environment }}'],
    ['3 min ago', '{{ triggeredAt }}'],
    ['High CPU load on checkout-api-7f9c2', '{{ metricName }} alert on {{ hostName }}'],
    ['Critical', '{{ severity }}'],
    ['CPU > 90% for 5m', '{{ condition }}'],
    ['production', '{{ environment }}'],
    ['CPU usage', '{{ metricName }}'],
    ['92.5%', '{{ currentValue }}'],
    ['&uarr; from 41% baseline &middot; sustained 5m 12s', '{{ baselineSummary }} &middot; sustained {{ sustainedDuration }}'],
    ['92.5% now', '{{ currentValue }} now'],
    ['90%', '{{ threshold }}'],
    ['Memory', '{{ memoryLabel }}'],
    ['Disk', '{{ diskLabel }}'],
    ['CPU', '{{ cpuLabel }}'],
    ['92<span', '{{ cpuPercent }}<span'],
    ['78<span', '{{ memoryPercent }}<span'],
    ['64<span', '{{ diskPercent }}<span'],
    ['7.82', '{{ load1m }}'],
    ['8 vCPU', '{{ vcpu }} vCPU'],
    ['checkout-api-7f9c2', '{{ hostName }}'],
    ['10.12.4.8', '{{ privateIp }}'],
    ['us-east-1a', '{{ region }}'],
    ['c6i.2xlarge &middot; 8 vCPU &middot; 16 GiB', '{{ instanceType }} &middot; {{ vcpu }} vCPU &middot; {{ memoryTotal }}'],
    ['Ubuntu 22.04 LTS', '{{ os }}'],
    ['moneat-agent v1.42.0', '{{ agentVersion }}'],
    ['23d 4h 11m', '{{ uptime }}'],
  ]);
  html = replaceMany(html, [
    ['v{{ cpuLabel }}', 'vCPU'],
    ['8 vCPU', '{{ vcpu }} vCPU'],
    ['c6i.2xlarge &middot; {{ vcpu }} vCPU &middot; 16 GiB', '{{ instanceType }} &middot; {{ vcpu }} vCPU &middot; {{ memoryTotal }}'],
  ]);
  html = html.replace(
    sparkline([38,40,39,42,41,44,46,45,48,52,55,58,61,64,68,72,76,80,84,88,90,92,93,92],19),
    'HOST_HISTORY_PLACEHOLDER',
  );
  const processStart = '<table role="presentation" width="100%" cellpadding="0" cellspacing="0">\n        <tr><td style="padding:0 0 8px;';
  const historyStart = '<div style="font:600 11px/1 \'JetBrains Mono\',ui-monospace,\'SF Mono\',Menlo,Consolas,monospace;letter-spacing:0.08em;text-transform:uppercase;color:#6b7280;">{{ cpuLabel }} &middot; last hour</div>';
  html = replaceSection(html, processStart, historyStart, 'HOST_PROCESSES_PLACEHOLDER<div style="height:20px;"></div>\n      ' + historyStart);
  html = html.replace(/<table role="presentation" width="100%" cellpadding="0" cellspacing="0">\s*<tr><td style="padding:0 0 8px;font:600 10px\/1 [\s\S]*?<\/table>\s*<div style="height:20px;"><\/div>\s*\s*<div style="font:600 11px\/1 [\s\S]*?CPU &middot; last hour<\/div>/, 'HOST_PROCESSES_PLACEHOLDER<div style="height:20px;"></div>' + label('{{ metricName }} &middot; last hour'));
  return setActionHrefs(
    setPrimaryHref(html, 'View host &rarr;', '{{ hostUrl }}'),
    [
      ['Open dashboard', '{{ hostUrl }}'],
      ['Silence 1h', '{{ hostUrl }}'],
      ['Runbook', '{{ hostUrl }}'],
    ],
  );
};

const parameterizeMonitorAlert = () => {
  let html = withCommonFooterTokens(emails['03-monitor-alert']);
  html = replaceMany(html, [
    ['[Production Overview] Alert &middot; 5xx error rate 15.2% over 10%', '[{{ dashboardName }}] Alert &middot; {{ metricName }} {{ currentValue }} over {{ threshold }}'],
    ['Monitor triggered &middot; 5xx error rate 15.2% (threshold 10%) over 5m avg', 'Monitor triggered &middot; {{ metricName }} {{ currentValue }} (threshold {{ threshold }}) {{ condition }}'],
    ['Production', '{{ environment }}'],
    ['1 min ago', '{{ triggeredAt }}'],
    ['5xx error rate above 10% on Production Overview', '{{ metricName }} above {{ threshold }} on {{ dashboardName }}'],
    ['5xx error rate &middot; 5m average', '{{ metricName }}'],
    ['15.2%', '{{ currentValue }}'],
    ['&uarr; from 2.1% baseline', '{{ baselineSummary }}'],
    ['15.2% now', '{{ currentValue }} now'],
    ['10%', '{{ threshold }}'],
    ['Production Overview', '{{ dashboardName }}'],
    ['HTTP 5xx error rate', '{{ widgetName }}'],
    ['avg over 5m > {{ threshold }}', '{{ condition }}'],
    ['every 1 min', '{{ cadence }}'],
    ['16:30:12 UTC', '{{ triggeredAt }}'],
    ['env:production service:checkout-api', '{{ scope }}'],
    ['env:{{ environment }} service:{{ projectName }}', '{{ scope }}'],
    ['Error rate &middot; last hour', '{{ metricName }} &middot; last hour'],
    ['Red bars indicate samples in breach of the {{ threshold }} threshold.', '{{ breachSummary }}'],
  ]);
  html = html.replace(
    sparkline([2,2,3,2,3,2,3,4,3,4,5,4,6,7,6,8,9,8,10,11,13,14,15,15],18),
    'MONITOR_HISTORY_PLACEHOLDER',
  );
  return setActionHrefs(
    setPrimaryHref(html, 'View monitor &rarr;', '{{ monitorUrl }}'),
    [
      ['Open dashboard', '{{ monitorUrl }}'],
      ['Silence', '{{ monitorUrl }}'],
      ['Edit monitor', '{{ monitorUrl }}'],
    ],
  );
};

const parameterizeResolvedAlert = () => {
  let html = withCommonFooterTokens(emails['04-resolved']);
  html = replaceMany(html, [
    ['[checkout-api-7f9c2] Resolved &middot; CPU recovered after 12m 04s', '[{{ targetName }}] Resolved &middot; {{ metricName }} recovered after {{ duration }}'],
    ['Recovered &middot; CPU back to 38.1% (peak 92.5%) &middot; alert open 12m 04s', 'Recovered &middot; {{ metricName }} back to {{ currentValue }} (peak {{ peakValue }}) &middot; alert open {{ duration }}'],
    ['Production &middot; us-east-1', '{{ environment }}'],
    ['CPU load on checkout-api-7f9c2 has recovered', '{{ metricName }} on {{ targetName }} has recovered'],
    ['12m 04s', '{{ duration }}'],
    ['92.5%', '{{ peakValue }}'],
    ['38.1%', '{{ currentValue }}'],
    ['CPU > 90% for 5m', '{{ monitorName }}'],
    ['16:30:12 UTC', '{{ triggeredAt }}'],
    ['16:42:16 UTC', '{{ recoveredAt }}'],
    ['Acknowledged by', 'Acknowledged by'],
    ['The metric returned to normal levels and the alert closed automatically. No further action is needed.', '{{ resolutionSummary }}'],
  ]);
  html = html.replace(/<td style="padding:9px 0;border-top:1px solid #e4e7ec;text-align:right;font:500 13px\/1.5 [^"]*;color:#161922;vertical-align:top;">&mdash;<\/td>/, '<td style="padding:9px 0;border-top:1px solid #e4e7ec;text-align:right;font:500 13px/1.5 ' + SANS + ';color:#161922;vertical-align:top;">{{ acknowledgedBy }}</td>');
  return setActionHrefs(
    setPrimaryHref(html, 'View incident history &rarr;', '{{ alertUrl }}'),
    [
      ['Open dashboard', '{{ alertUrl }}'],
      ['Mute monitor', '{{ alertUrl }}'],
    ],
  );
};

const parameterizeWeeklyDigest = () => {
  let html = withCommonFooterTokens(emails['05-weekly-digest']);
  html = replaceMany(html, [
    ['Your week in review &middot; Jun 9 &ndash; Jun 15', 'Your week in review &middot; {{ startDate }} - {{ endDate }}'],
    ['1.84M events &middot; 23 new issues (&darr;12%) &middot; 1,204 users affected across 4 services', '{{ totalEvents }} events &middot; {{ newIssues }} new issues &middot; {{ affectedUsers }} users affected'],
    ['Jun 9 &ndash; Jun 15', '{{ startDate }} - {{ endDate }}'],
    ['<div style="font:600 21px/1.1 \'JetBrains Mono\',ui-monospace,\'SF Mono\',Menlo,Consolas,monospace;color:#0e1016;letter-spacing:-0.01em;">1.84M <span', '<div style="font:600 21px/1.1 \'JetBrains Mono\',ui-monospace,\'SF Mono\',Menlo,Consolas,monospace;color:#0e1016;letter-spacing:-0.01em;">{{ totalEvents }} <span'],
    ['>&uarr; 6%</span></div>\n      <div style="margin-top:5px;font:500 10px/1.3', '>EVENTS_TREND_PLACEHOLDER</span></div>\n      <div style="margin-top:5px;font:500 10px/1.3'],
    ['<div style="font:600 21px/1.1 \'JetBrains Mono\',ui-monospace,\'SF Mono\',Menlo,Consolas,monospace;color:#0e1016;letter-spacing:-0.01em;">23 <span', '<div style="font:600 21px/1.1 \'JetBrains Mono\',ui-monospace,\'SF Mono\',Menlo,Consolas,monospace;color:#0e1016;letter-spacing:-0.01em;">{{ newIssues }} <span'],
    ['>&darr; 12%</span></div>\n      <div style="margin-top:5px;font:500 10px/1.3', '>ISSUES_TREND_PLACEHOLDER</span></div>\n      <div style="margin-top:5px;font:500 10px/1.3'],
    ['<div style="font:600 21px/1.1 \'JetBrains Mono\',ui-monospace,\'SF Mono\',Menlo,Consolas,monospace;color:#0e1016;letter-spacing:-0.01em;">1,204 <span', '<div style="font:600 21px/1.1 \'JetBrains Mono\',ui-monospace,\'SF Mono\',Menlo,Consolas,monospace;color:#0e1016;letter-spacing:-0.01em;">{{ affectedUsers }} <span'],
    ['>&uarr; 3%</span></div>\n      <div style="margin-top:5px;font:500 10px/1.3', '>USERS_TREND_PLACEHOLDER</span></div>\n      <div style="margin-top:5px;font:500 10px/1.3'],
  ]);
  const topIssuesStart = '<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr>\n        <td style="padding:11px 0;width:14px;';
  const byServiceStart = '<div style="font:600 11px/1 \'JetBrains Mono\',ui-monospace,\'SF Mono\',Menlo,Consolas,monospace;letter-spacing:0.08em;text-transform:uppercase;color:#6b7280;">By service</div>';
  html = replaceSection(html, topIssuesStart, byServiceStart, 'ISSUES_PLACEHOLDER</td></tr><tr><td class="px" style="padding:20px 28px 20px;background:#f7f8fa;border-top:1px solid #e4e7ec;">' + byServiceStart);
  const projectTableStart = '<table role="presentation" width="100%" cellpadding="0" cellspacing="0">\n        <tr><td style="padding:0 0 9px;';
  const projectTableEnd = '</td></tr><tr><td class="px" style="padding:20px 28px 20px;background:#ffffff;border-top:1px solid #e4e7ec;"><table role="presentation" cellpadding="0" cellspacing="0"><tr><td bgcolor="#161922"';
  html = replaceSection(html, projectTableStart, projectTableEnd, 'PROJECTS_PLACEHOLDER</td></tr><tr><td class="px" style="padding:20px 28px 20px;background:#ffffff;border-top:1px solid #e4e7ec;"><table role="presentation" cellpadding="0" cellspacing="0"><tr><td bgcolor="#161922"');
  html = html.replace('organization Acme Corp', 'organization {{ organizationName }}');
  return setActionHrefs(
    setPrimaryHref(html, 'Open dashboard &rarr;', '{{ dashboardUrl }}'),
    [
      ['Customize digest', '{{ settingsUrl }}'],
      ['Change frequency', '{{ settingsUrl }}'],
    ],
  );
};

const parameterizeBilling = (source, threshold = false) => {
  let html = withCommonFooterTokens(source);
  html = replaceMany(html, [
    ['Billing &middot; You have used 92% of included log volume', 'Billing &middot; {{ headline }}'],
    ['Logs 46.2 / 50 GB used (92%) &middot; Team plan &middot; period Jun 1 &ndash; Jun 30', '{{ summary }} &middot; {{ plan }} plan &middot; period {{ periodStart }} - {{ periodEnd }}'],
    ['Usage insights &middot; Span ingestion up 38% this period', 'Usage insights &middot; {{ headline }}'],
    ['Span ingestion up 38%, driven by checkout-api &middot; all usage within your plan', '{{ summary }}'],
    ['You have used 92% of your included log volume', '{{ headline }}'],
    ['Span ingestion is up 38% this period', '{{ headline }}'],
    ['At the current rate you are on track to exceed the included volume before the period ends. Overage is billed at $0.40 / GB.', '{{ summary }}'],
    ['checkout-api drove most of the increase after the v2.4 rollout. You are still comfortably within your Team plan &mdash; no action needed.', '{{ summary }}'],
    ['Team plan', '{{ plan }} plan'],
    ['Billable usage &middot; Jun 1 &ndash; Jun 30', 'Billable usage &middot; {{ periodStart }} - {{ periodEnd }}'],
  ]);
  const rowStart = html.indexOf('\n        <div style="">\n          <table role="presentation" width="100%"');
  const nextSection = html.indexOf(
    '</td></tr><tr><td class="px" style="padding:20px 28px 20px;background:#ffffff;border-top:1px solid #e4e7ec">',
    rowStart,
  );
  if (rowStart !== -1 && nextSection !== -1) {
    html = html.slice(0, rowStart) + '\n        BILLING_ROWS_PLACEHOLDER' + html.slice(nextSection);
  }
  html = replaceMany(html, [
    ['Team &middot; $0.40 / GB logs overage', '{{ plan }}'],
    ['Jun 1 &ndash; Jun 30', '{{ periodStart }} - {{ periodEnd }}'],
    ['&asymp; $6.40', '{{ totalOverage }}'],
    ['checkout-api', '{{ topDriver }}'],
    ['112M &uarr; 38%', '{{ topDriverUsage }}'],
    ['Team &middot; within included usage', '{{ plan }}'],
    ['$0.00', '{{ totalOverage }}'],
  ]);
  return setActionHrefs(
    setPrimaryHref(html, threshold ? 'Review usage &rarr;' : 'Open usage insights &rarr;', '{{ dashboardUrl }}'),
    [
      ['Manage plan', '{{ settingsUrl }}'],
      ['Set usage alerts', '{{ settingsUrl }}'],
    ],
  );
};

const parameterizeVerifyEmail = () => {
  let html = withCommonFooterTokens(emails['08-verify-email']);
  html = replaceMany(html, [
    ['Hi Alex &mdash; thanks for signing up. Confirm your email address to finish setting up your Moneat account and start sending telemetry.', 'Hi {{ userName }}. Confirm your email address to finish setting up your Moneat account and start sending telemetry.'],
    ['https://app.moneat.io/verify?token=8f3a1c9e2b7d4f60a5c1e9d8', '{{ verificationUrl }}'],
  ]);
  return setPrimaryHref(html, 'Verify email address', '{{ verificationUrl }}');
};

const parameterizeResetPassword = () => {
  let html = withCommonFooterTokens(emails['09-reset-password']);
  html = replaceMany(html, [
    ['Hi Alex &mdash; we received a request to reset the password on your Moneat account. Choose a new one below.', 'Hi {{ userName }}. We received a request to reset the password on your Moneat account. Choose a new one below.'],
    ['https://app.moneat.io/reset-password?token=a5c1e9d88f3a1c9e2b7d4f60', '{{ resetUrl }}'],
  ]);
  return setPrimaryHref(html, 'Reset password', '{{ resetUrl }}');
};

const parameterizeInvitation = () => {
  let html = withCommonFooterTokens(emails['10-org-invitation']);
  html = replaceMany(html, [
    ['You have been invited to join Acme Corp on Moneat', 'You have been invited to join {{ orgName }} on Moneat'],
    ['Sarah Chen invited you to join Acme Corp on Moneat as an Admin.', '{{ inviterName }} invited you to join {{ orgName }} on Moneat as {{ role }}.'],
    ['You\'re invited to join Acme&nbsp;Corp', 'You\'re invited to join {{ orgName }}'],
    ['Sarah Chen', '{{ inviterName }}'],
    ['Acme Corp', '{{ orgName }}'],
    ['Admin', '{{ role }}'],
    ['sarah@acme.com', '{{ inviterEmail }}'],
    ['https://app.moneat.io/accept-invite?token=abc123def456ghi789', '{{ inviteUrl }}'],
  ]);
  return setPrimaryHref(html, 'Accept invitation', '{{ inviteUrl }}');
};

/* ============================== WRITE ============================== */
const scriptDir = dirname(fileURLToPath(import.meta.url));
const templatesDir = join(scriptDir, '..', 'src', 'templates');
mkdirSync(templatesDir, { recursive: true });

const outputTemplates = {
  'error-alert.html': parameterizeIssueAlert(),
  'host-alert-v1.html': parameterizeHostAlert(),
  'system-alert-v1.html': parameterizeHostAlert(),
  'dashboard-alert-v1.html': parameterizeMonitorAlert(),
  'host-recovered.html': parameterizeResolvedAlert(),
  'system-recovered.html': parameterizeResolvedAlert(),
  'weekly-summary.html': parameterizeWeeklyDigest(),
  'billing-threshold-alert.html': parameterizeBilling(emails['06-billing-threshold'], true),
  'billing-insights.html': parameterizeBilling(emails['07-billing-insights']),
  'verify-email.html': parameterizeVerifyEmail(),
  'reset-password.html': parameterizeResetPassword(),
  'org-invitation.html': parameterizeInvitation(),
};

for (const [name, html] of Object.entries(outputTemplates)) {
  writeFileSync(join(templatesDir, name), cleanupGenerated(html), 'utf8');
}

console.log(
  `Wrote ${Object.keys(outputTemplates).length} redesigned Maizzle templates to ${templatesDir}`,
);
