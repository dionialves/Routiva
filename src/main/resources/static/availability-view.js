/* Presentation only: classification comes from the backend. */
window.AvailabilityView = (() => {
  const $ = id => document.getElementById(id);
  const format = (value, unit = '%') => value == null ? 'Sem dados' : value.toLocaleString('pt-BR', {minimumFractionDigits:2, maximumFractionDigits:2}) + unit;
  const states = {
    NORMAL:['Normal','normal'], DEGRADADO:['Atenção','warning'], CRITICO:['Crítico','critical'],
    SEM_DADOS:['Sem dados','neutral'], SEM_IP:['Sem IP válido','neutral'], RECONHECIDO:['Incidente reconhecido','neutral'],
    FALHA_COLETA:['Falha na coleta','neutral'], ATRASADO:['Coleta atrasada','warning']
  };
  let tableVersion;
  function table(data) {
    const {view, page} = data;
    const fingerprint = JSON.stringify([view.hosts, page]);
    if (fingerprint === tableVersion) return;
    tableVersion = fingerprint;
    const fragment = document.createDocumentFragment();
    for (const row of view.hosts) {
      const tr = document.createElement('tr'), th = document.createElement('th'), link = document.createElement('a'), ip = document.createElement('small');
      th.scope = 'row'; link.href = '/hosts/' + encodeURIComponent(row.id); link.textContent = row.name; ip.textContent = row.ip || 'Sem IP'; th.append(link,ip); tr.append(th);
      const group = document.createElement('td'); group.textContent = (row.groups || []).map(g=>g.name).join(', ') || 'Sem grupo'; group.className='host-groups'; tr.append(group);
      for (const key of ['operational','technical','loss','latency']) {
        const td = document.createElement('td'); td.className='availability-number';
        const wrap = document.createElement('div'); wrap.className='availability-percent';
        if (['operational','technical'].includes(key) && row.metrics[key] != null) {
          const track=document.createElement('span'), fill=document.createElement('span'); track.className='availability-meter '+key; track.setAttribute('aria-hidden','true'); fill.style.width=Math.max(0,Math.min(100,row.metrics[key]))+'%'; track.append(fill); wrap.append(track);
        }
        const value=document.createElement('span'); value.textContent=format(row.metrics[key],key==='latency'?' ms':'%'); wrap.append(value); td.append(wrap); tr.append(td);
      }
      const td=document.createElement('td'), badge=document.createElement('span'), state=states[row.status]||states.SEM_DADOS;
      badge.className='host-status '+state[1]; badge.textContent=state[0]; td.append(badge); tr.append(td); fragment.append(tr);
    }
    $('availability-hosts').replaceChildren(fragment);
    $('host-count').textContent=`${page.total} de ${page.selectedTotal} hosts`;
    $('hosts-empty').hidden=page.total!==0;
    $('hosts-empty').textContent=page.selectedTotal===0?'Nenhum host disponível para este grupo.':'Nenhum host corresponde à pesquisa.';
    $('page-status').textContent=`Página ${page.number} de ${page.pages}`;
    $('previous').disabled=page.number<=1; $('next').disabled=page.number>=page.pages;
  }
  function reset() {tableVersion=null; $('availability-hosts').replaceChildren(); $('hosts-empty').hidden=true; $('previous').disabled=true; $('next').disabled=true;}
  return {table,reset,format};
})();
