(() => {
  'use strict';
  const $=id=>document.getElementById(id), main=document.querySelector('.availability-page');
  const params=new URLSearchParams(location.search), periods=['5','60','480','1440','2880'];
  $('period').value=periods.includes(params.get('minutes'))?params.get('minutes'):'2880';
  let group=params.get('groupId')||'', page=Math.max(1,Number(params.get('page'))||1), sort=params.get('sort')||'name', direction=params.get('direction')==='desc'?'desc':'asc';
  $('host-search').value=params.get('q')||'';
  if(group){$('group').add(new Option('Grupo selecionado',group));$('group').value=group;window.availabilityGroupPicker?.sync();}
  let data=null, controller, generation=0, timer, searchTimer, groupVersion;
  const chart=new AvailabilityChart.Chart($('availability-chart'),$('chart-granularity'));
  const date=at=>new Date(at).toLocaleString('pt-BR');
  function query() {return new URLSearchParams({minutes:$('period').value,groupId:group,page:String(page),sort,direction,q:$('host-search').value});}
  function url() {history.replaceState(null,'',location.pathname+'?'+query());}
  function loading(value) {main.classList.toggle('is-loading',value); main.setAttribute('aria-busy',String(value));}
  function render() {
    const r=data.view.report;
    for(const key of ['operational','technical','loss','latency']) $(key).textContent=AvailabilityView.format(r.total[key],key==='latency'?' ms':'%');
    $('availability-range').textContent=r.evaluatedAt?`Período: ${date(r.from)} → ${date(r.to)}`:'Aguardando a primeira publicação de indicadores.';
    $('last-measurement').textContent='Última medição: '+(r.lastSampleAt?date(r.lastSampleAt):'Sem medições');
    const stale=r.evaluatedAt!=null&&Date.now()-r.evaluatedAt>30000;
    const state=stale?'ATRASADO':data.collectionStatus;
    const labels={RECEBENDO:'Coleta recebendo medições',PARCIAL:'Coleta parcial ou atrasada',SEM_HOSTS:'Nenhum host monitorado',SEM_DADOS:'Sem medições disponíveis',CARREGANDO:'Carregando indicadores',ATRASADO:'Indicadores desatualizados'};
    $('collection-state').textContent=labels[state]||'Estado da coleta indisponível';
    $('collection-state').dataset.state=state==='RECEBENDO'?'normal':state==='PARCIAL'||state==='ATRASADO'?'warning':'neutral';
    $('availability-status').textContent=stale?'A publicação dos indicadores está atrasada.':r.evaluatedAt?`Indicadores atualizados em ${date(r.evaluatedAt)}`:'Carregando indicadores…';
    $('availability-coverage').textContent=[r.load&&r.load.phase!=='COMPLETE'?'Histórico em carregamento; os dados ainda são parciais.':'',r.retainedFrom>r.from?'O histórico retido é menor que o período selecionado.':'',r.total.technical==null&&data.page.selectedTotal>0?'Os hosts selecionados não possuem medições válidas neste período.':''].filter(Boolean).join(' ');
    chart.update(r); AvailabilityView.table(data); page=data.page.number;
    loading(r.evaluatedAt==null);
  }
  async function refresh(reset=false) {
    clearTimeout(timer); clearTimeout(searchTimer); controller?.abort(); controller=new AbortController(); const active=controller, current=++generation;
    $('retry').hidden=true;
    if(reset) {$('collection-state').textContent='Aguardando indicadores';$('collection-state').dataset.state='neutral';$('last-measurement').textContent='Última medição: —';data=null;chart.clear();AvailabilityView.reset();loading(true);for(const key of ['operational','technical','loss','latency']) $(key).textContent='—';$('availability-coverage').textContent='';$('availability-range').textContent='';}
    if(!data) {$('availability-status').textContent='Carregando indicadores…';loading(true);}
    const timeout=setTimeout(()=>active.abort(),9000);
    try {
      const response=await fetch('/api/availability/dashboard?'+query(),{signal:active.signal,cache:'no-store'});
      if(!response.ok) throw new Error(response.status===404?'Grupo indisponível. Selecione outro grupo.':'Não foi possível atualizar os indicadores.');
      const result=await response.json();if(current!==generation)return;
      const options=JSON.stringify(result.groups);
      if(options!==groupVersion) {groupVersion=options;$('group').replaceChildren(new Option('Todos os hosts',''));for(const g of result.groups)$('group').add(new Option(g.name,g.id));if(group&&!result.groups.some(g=>g.id===group))$('group').add(new Option('Grupo indisponível',group));$('group').value=group;window.availabilityGroupPicker?.sync();}
      if(result.view){data=result;render();} else {$('collection-state').textContent='Carregando indicadores';}
    } catch(error) {
      if(current!==generation)return;
      loading(false);$('retry').hidden=false;
      $('availability-status').textContent=(error.name==='AbortError'?'A consulta demorou mais que o esperado.':error.message)+(data?' Exibindo os últimos dados recebidos.':'')+' Nova tentativa em 10 segundos.';
      $('collection-state').textContent='Estado da coleta indisponível';$('collection-state').dataset.state='neutral';
    } finally {clearTimeout(timeout);if(current===generation)timer=setTimeout(()=>refresh(),10000);}
  }
  document.querySelector('.availability-filters').addEventListener('submit',e=>e.preventDefault());
  for(const id of ['period','group'])$(id).addEventListener('change',()=>{group=$('group').value;page=1;url();refresh(true);});
  $('host-search').addEventListener('input',()=>{clearTimeout(searchTimer);clearTimeout(timer);controller?.abort();generation++;searchTimer=setTimeout(()=>{page=1;url();refresh();},300);});
  document.querySelectorAll('[data-sort]').forEach(button=>button.addEventListener('click',()=>{direction=sort===button.dataset.sort&&direction==='asc'?'desc':'asc';sort=button.dataset.sort;page=1;document.querySelectorAll('[data-sort]').forEach(b=>b.parentElement.setAttribute('aria-sort',b===button?(direction==='asc'?'ascending':'descending'):'none'));url();refresh();}));
  $('previous').addEventListener('click',()=>{page=Math.max(1,page-1);url();refresh();});$('next').addEventListener('click',()=>{page++;url();refresh();});$('retry').addEventListener('click',()=>refresh());
  for(const mode of ['lines','bars'])$('chart-'+mode).addEventListener('click',()=>{chart.mode=mode;chart.draw();for(const item of ['lines','bars'])$('chart-'+item).setAttribute('aria-pressed',String(item===mode));});
  const panel=document.querySelector('.availability-timeseries');
  function expand(value){chart.expanded=value;document.querySelectorAll('.availability-page > :not(.availability-timeseries), .sidebar, .topbar').forEach(el=>el.inert=value);panel.classList.toggle('chart-expanded',value);$('chart-expand').setAttribute('aria-expanded',String(value));$('chart-expand').setAttribute('aria-label',value?'Recolher gráfico':'Expandir gráfico');document.body.classList.toggle('chart-is-expanded',value);panel.setAttribute('role',value?'dialog':'region');panel.setAttribute('aria-modal',String(value));chart.draw();}
  $('chart-expand').addEventListener('click',()=>expand(!panel.classList.contains('chart-expanded')));
  const mobile=window.matchMedia?.('(max-width:760px)');
  function menu(open){$('sidebar').inert=!!mobile?.matches&&!open;main.inert=open;$('mobile-menu').setAttribute('aria-label',open?'Fechar menu':'Abrir menu');document.body.classList.toggle('availability-menu-open',open);$('mobile-menu').setAttribute('aria-expanded',String(open));$('menu-backdrop').hidden=!open;}
  $('mobile-menu').addEventListener('click',()=>menu(!document.body.classList.contains('availability-menu-open')));$('menu-backdrop').addEventListener('click',()=>menu(false));
  mobile?.addEventListener('change',()=>menu(false));menu(false);
  document.addEventListener('keydown',e=>{if(e.key==='Tab'&&chart.expanded){const controls=[...panel.querySelectorAll('button,select,[tabindex="0"]')];const first=controls[0],last=controls.at(-1);if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}}if(e.key==='Escape'){expand(false);menu(false);}});
  document.querySelectorAll('[data-sort]').forEach(b=>b.parentElement.setAttribute('aria-sort',b.dataset.sort===sort?(direction==='asc'?'ascending':'descending'):'none'));
  refresh();
})();
