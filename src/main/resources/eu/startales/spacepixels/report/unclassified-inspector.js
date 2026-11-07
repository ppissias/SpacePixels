/* The Unclassified Transient Inspector: marker details, tooltips and the cutout animation. */
(function(){
const panel=document.getElementById('unclassified-transient-inspector');
const map=panel&&panel.querySelector('#unclassified-map');
if(!map)return;
const image=panel.querySelector('#unclassified-background');
const tooltip=panel.querySelector('#unclassified-tooltip');
const metadata=panel.querySelector('#unclassified-metadata');
const canvas=panel.querySelector('#unclassified-cutout');
const markers=Array.from(panel.querySelectorAll('.unclassified-marker'));
let selected=null;
function setDetails(marker){
const data=marker.dataset;
const rows=[['Status','UNCLASSIFIED'],['Transient ID',data.id],['Frame',data.frame+' (index '+data.index+')'],['Timestamp',data.time],['Source file',data.filename],['X',data.x],['Y',data.y],['Peak sigma',data.peak],['Integrated sigma',data.integrated],['Total flux',data.flux],['Pixel count',data.pixels],['Elongation',data.elongation],['FWHM',data.fwhm]];
metadata.replaceChildren();
rows.forEach(([label,value])=>{const term=document.createElement('dt');const detail=document.createElement('dd');term.textContent=label;detail.textContent=value;metadata.append(term,detail);});
}
function drawCutout(marker){
if(!image.complete||!image.naturalWidth)return;
const context=canvas.getContext('2d');const size=80;
const sourceWidth=Math.min(size,image.naturalWidth);const sourceHeight=Math.min(size,image.naturalHeight);
const x=Number(marker.dataset.x);const y=Number(marker.dataset.y);
const left=Math.max(0,Math.min(image.naturalWidth-sourceWidth,Math.round(x-sourceWidth/2)));
const top=Math.max(0,Math.min(image.naturalHeight-sourceHeight,Math.round(y-sourceHeight/2)));
context.clearRect(0,0,canvas.width,canvas.height);context.imageSmoothingEnabled=false;
context.drawImage(image,left,top,sourceWidth,sourceHeight,0,0,canvas.width,canvas.height);
const footprint=marker.parentElement.querySelector('.unclassified-footprint');
if(footprint){context.save();context.setTransform(canvas.width/sourceWidth,0,0,canvas.height/sourceHeight,-left*canvas.width/sourceWidth,-top*canvas.height/sourceHeight);context.fillStyle=marker.getAttribute('stroke');context.globalAlpha=.85;context.fill(new Path2D(footprint.getAttribute('d')));context.restore();}
const markerX=(x-left)*canvas.width/sourceWidth;const markerY=(y-top)*canvas.height/sourceHeight;
context.strokeStyle='#ffffff';context.lineWidth=3;context.beginPath();context.arc(markerX,markerY,13,0,Math.PI*2);context.stroke();
context.strokeStyle=marker.getAttribute('stroke');context.lineWidth=2;context.beginPath();context.arc(markerX,markerY,17,0,Math.PI*2);context.stroke();
}
const controls=panel.querySelector('#unclassified-cutout-controls');const playButton=panel.querySelector('#unclassified-play');
const viewButton=panel.querySelector('#unclassified-view');const note=panel.querySelector('#unclassified-cutout-note');
const staticLabel=viewButton.textContent;const strips={};const delay=Number(panel.dataset.frameDelay);
let timer=null;let frame=0;let playing=true;let showStatic=false;let globalPaused=false;
function stopTimer(){if(timer){clearInterval(timer);timer=null;}}
function drawStripFrame(marker){
const data=marker.dataset;const strip=strips[data.strip];if(!strip||!strip.complete||!strip.naturalWidth)return;
const count=Number(data.stripCount);const w=strip.naturalWidth;const h=strip.naturalHeight/count;const context=canvas.getContext('2d');
context.clearRect(0,0,canvas.width,canvas.height);context.imageSmoothingEnabled=false;
context.drawImage(strip,0,frame*h,w,h,0,0,canvas.width,canvas.height);
const markerX=(Number(data.x)-Number(data.stripLeft))*canvas.width/w;const markerY=(Number(data.y)-Number(data.stripTop))*canvas.height/h;
const detection=frame===Number(data.stripDetection);
context.strokeStyle=detection?marker.getAttribute('stroke'):'rgba(255,255,255,.55)';context.lineWidth=detection?3:1.5;context.beginPath();context.arc(markerX,markerY,17,0,Math.PI*2);context.stroke();
const frames=data.stripFrames.split(',').map(Number);const offset=frames[frame]-frames[Number(data.stripDetection)];const label='Frame '+frames[frame]+(offset===0?' · detection':' ('+(offset>0?'+':'')+offset+')');
context.font='bold 14px sans-serif';const textWidth=context.measureText(label).width;context.fillStyle='rgba(10,12,20,.75)';context.fillRect(6,6,textWidth+12,22);
context.fillStyle=detection?marker.getAttribute('stroke'):'#ffffff';context.fillText(label,12,22);
}
function startAnimation(marker){
const data=marker.dataset;let strip=strips[data.strip];
if(!strip){strip=new Image();strip.onload=()=>{if(selected===marker&&!showStatic)drawStripFrame(marker);};strip.src=data.strip;strips[data.strip]=strip;}
drawStripFrame(marker);
if(playing&&!globalPaused)timer=setInterval(()=>{frame=(frame+1)%Number(data.stripCount);drawStripFrame(marker);},delay);
}
function render(marker){
stopTimer();const data=marker.dataset;const animated=!!data.strip;controls.hidden=!animated;
if(animated&&!showStatic){startAnimation(marker);const frames=data.stripFrames.split(',');
note.textContent='80 × 80 pixel cutout from frames '+frames.join(', ')+', detection in frame '+frames[Number(data.stripDetection)]+'. Frames rejected by quality control are skipped; all frames share one stretch. Click the image to step when paused.';}
else{drawCutout(marker);note.textContent=note.dataset.static;}
playButton.textContent=playing?'Pause':'Play';playButton.disabled=showStatic;viewButton.textContent=showStatic?'Show frames':staticLabel;
}
playButton.addEventListener('click',()=>{playing=!playing;if(selected)render(selected);});
document.addEventListener('spacepixels-animation-state',event=>{globalPaused=event.detail.paused;if(selected)render(selected);});
viewButton.addEventListener('click',()=>{showStatic=!showStatic;if(selected)render(selected);});
canvas.addEventListener('click',()=>{if(selected&&selected.dataset.strip&&!showStatic&&!playing){frame=(frame+1)%Number(selected.dataset.stripCount);drawStripFrame(selected);}});
function select(marker){if(selected){selected.classList.remove('selected');selected.parentElement.classList.remove('selected');}selected=marker;marker.classList.add('selected');marker.parentElement.classList.add('selected');setDetails(marker);frame=Number(marker.dataset.stripDetection||0);render(marker);}
function positionTooltip(event){const bounds=map.getBoundingClientRect();const left=Math.min(event.clientX-bounds.left+12,bounds.width-tooltip.offsetWidth-8);const top=Math.min(event.clientY-bounds.top+12,bounds.height-tooltip.offsetHeight-8);tooltip.style.left=Math.max(8,left)+'px';tooltip.style.top=Math.max(8,top)+'px';}
function showTooltip(marker,event){const data=marker.dataset;tooltip.textContent=data.id+' · Frame '+data.frame+' · '+data.time+'\nX '+data.x+', Y '+data.y+' · Peak '+data.peak+'σ · '+data.pixels+' pixels';tooltip.style.display='block';if(event&&typeof event.clientX==='number')positionTooltip(event);}
markers.forEach(marker=>{marker.addEventListener('mouseenter',event=>showTooltip(marker,event));marker.addEventListener('mousemove',positionTooltip);marker.addEventListener('mouseleave',()=>tooltip.style.display='none');marker.addEventListener('focus',()=>showTooltip(marker));marker.addEventListener('blur',()=>tooltip.style.display='none');marker.addEventListener('click',()=>select(marker));marker.addEventListener('keydown',event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();select(marker);}});});
image.addEventListener('load',()=>{if(selected&&(showStatic||!selected.dataset.strip))drawCutout(selected);});
if(markers.length)select(markers[0]);
})();
