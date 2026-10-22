(() => {
 'use strict';
 const report = window.hostReport;
 const number = value => value == null ? 'Indefinida' : new Intl.NumberFormat('pt-BR', {minimumFractionDigits: 2, maximumFractionDigits: 4}).format(value);
 for (const key of ['operational', 'technical', 'loss', 'degradation']) document.getElementById(key).textContent = number(report.total[key]) + (report.total[key] == null ? '' : '%');
 document.getElementById('counts').textContent = `${report.total.healthy} saudáveis · ${report.total.degraded} degradados · ${report.total.lost} perdidos`;
 document.getElementById('no-data').hidden = report.total.healthy + report.total.degraded + report.total.lost > 0;
 const time = value => new Date(value).toLocaleString('pt-BR', {day:'2-digit',month:'2-digit',hour:'2-digit',minute:'2-digit'});
 document.getElementById('date-range').textContent = `${time(report.from)} — ${time(report.to)}`;
 const ns = 'http://www.w3.org/2000/svg';
 function element(name, attrs, text) { const el = document.createElementNS(ns, name); for (const [key,value] of Object.entries(attrs)) el.setAttribute(key,value); if (text != null) el.textContent = text; return el; }
 function chart(id, lines, unit, fixedMax) {
  const svg = element('svg', {viewBox:'0 0 800 270', role:'img', 'aria-label':document.getElementById(id).parentElement.querySelector('h2').textContent});
  const max = fixedMax || Math.max(1, ...report.series.map(p => p.metrics.latency || 0)) * 1.1;
  const x = i => 65 + (i + .5) / report.series.length * 710;
  const y = v => 215 - v / max * 185;
  for (let i=0;i<=4;i++) { const value = max*i/4; svg.append(element('line',{x1:65,x2:775,y1:y(value),y2:y(value),stroke:'#2c394e'}),element('text',{x:55,y:y(value)+4,'text-anchor':'end',fill:'#97a5bd','font-size':12},`${number(value)}${unit}`)); }
  for (const [key,color,label] of lines) {
   let path=''; let connected=false;
   report.series.forEach((point,i) => {const value=point.metrics[key]; if(value==null){connected=false;return;} path+=`${connected?'L':'M'}${x(i)},${y(value)} `;connected=true;
    const dot=element('circle',{cx:x(i),cy:y(value),r:2,fill:color});dot.append(element('title',{},`${time(point.at)} · ${label}: ${number(value)}${unit} · H ${point.metrics.healthy}, D ${point.metrics.degraded}, P ${point.metrics.lost}`));svg.append(dot);
   });
   svg.append(element('path',{d:path,fill:'none',stroke:color,'stroke-width':2}));
  }
  [0,.5,1].forEach((fraction,i)=>svg.append(element('text',{x:65+fraction*710,y:250,'text-anchor':i===0?'start':i===2?'end':'middle',fill:'#97a5bd','font-size':12},time(report.from+fraction*(report.to-report.from)))));
  document.getElementById(id).append(svg);
 }
 chart('availability-chart',[['operational','#4ee4b1','Operacional'],['technical','#7dafff','Técnica']], '%',100);
 chart('loss-chart',[['loss','#ff7b85','Perda'],['degradation','#ffd166','Degradação']], '%',100);
 chart('latency-chart',[['latency','#7dafff','Latência média']], ' ms');
})();
