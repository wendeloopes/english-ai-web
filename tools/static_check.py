#!/usr/bin/env python3
"""English Ai static checks. Run: python3 tools/static_check.py  (exit 1 on any failure). Does NOT replace a real Gradle build."""
import re, subprocess, sys, xml.dom.minidom, os, json
R=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
A=os.path.join(R,'android-native/app'); K=A+'/src/main/java/com/englishai/app/'; W=A+'/src/main/assets/www/'
rd=lambda p:open(p,encoding='utf-8').read()
ok=True
def chk(name,cond,info=''):
    global ok; ok&=bool(cond); print(('PASS ' if cond else 'FAIL ')+name+(' - '+info if info and not cond else ''))
g=rd(A+'/build.gradle.kts')
chk('gradle versionCode=7 / versionName=1.3.4','versionCode = 7' in g and 'versionName = "1.3.4"' in g)
chk('litertlm dependency 0.18.0','litertlm-android:0.18.0' in g)
chk('ML Kit translate dependency','com.google.mlkit:translate:17.0.3' in g)
nb,en,ma=rd(K+'NativeBridge.kt'),rd(K+'LiteRtEngine.kt'),rd(K+'MainActivity.kt')
chk('old memory check removed',not re.search(r'estimated minimum|requiredHeadroom|availMem',nb+en+ma))
chk('safe import: backup + rollback','model.bak' in nb and 'previous model was restored' in nb)
chk('disk space check before copy','StatFs' in nb)
chk('copy size verified','The copy was interrupted' in nb)
chk('engine: maxNumTokens + cache + reuse','maxNumTokens' in en and 'litert-cache' in en and 'convoSystem' in en)
chk('engine lifecycle: assign only after initialize; do not close failed candidate', 'candidate.initialize()\n        engine = candidate' in en and 'candidate.close()' not in en)
chk('cancellation flag is visible across UI and model worker threads','@Volatile private var cancelled = false' in en and 'fun cancel() { cancelled = true;' in en and 'conversation?.cancelProcess()' in en and 'fun cancelGenerate() { cancelled = true; engine.cancel() }' in nb)
chk('background unload is guarded by foreground lifecycle state','private val lifecycleLock = Any()' in nb and 'if (!foreground && modelFile.exists()' in nb and 'fun setForeground(isForeground: Boolean)' in nb)
chk('model generation is serialized on the same worker as lifecycle operations','@JavascriptInterface fun generate(json: String) { cancelled = false; worker.execute { try {' in nb and 'fun unloadForBackground() { worker.execute {' in nb and 'fun removeModel() { worker.execute {' in nb)
chk('model resume is queued off the Android main thread','foreground = isForeground' in nb and 'if (isForeground) worker.execute' in nb)
chk('resume during engine shutdown schedules a second recovery check','// The user may have returned while engine.close() was running.' in nb and 'if (foreground) {' in nb and nb.index('if (foreground) {') > nb.index('fun unloadForBackground()'))
chk('activity updates bridge foreground state on start/stop','bridge.setForeground(false)' in ma and 'bridge.setForeground(true)' in ma and 'bridge.reloadIfUnloaded()' not in ma)
chk('bridge: getInfo/getLastCrash/copyText/openUrl',all(x in nb for x in ['fun getInfo','fun getLastCrash','fun copyText','fun openUrl']))
chk('bridge: on-device Portuguese translation',all(x in nb for x in ['fun ptEnsure','fun translate']))
for f,t in (('NativeBridge.kt',nb),('LiteRtEngine.kt',en),('MainActivity.kt',ma)):
    t2=re.sub(r'//.*','',re.sub(r'"(?:\\.|[^"\\])*"','""',t)); chk('kotlin balanced braces/parens '+f,t2.count('{')==t2.count('}') and t2.count('(')==t2.count(')'))
mf=rd(A+'/src/main/AndroidManifest.xml')
try: xml.dom.minidom.parseString(mf); chk('AndroidManifest is valid XML',True)
except Exception as e: chk('AndroidManifest is valid XML',False,str(e))
chk('manifest permissions','RECORD_AUDIO' in mf and 'INTERNET' in mf and 'ACCESS_NETWORK_STATE' in mf)
h=rd(W+'index.html'); js='\n'.join(re.findall(r'<script>(.*?)</script>',h,re.S)); open('/tmp/_chk.js','w').write(js)
chk('index.html inline JS parses (node --check)',subprocess.run(['node','--check','/tmp/_chk.js'],capture_output=True).returncode==0)
chk('html<->bridge: every NativeBridge.x() used in JS exists in Kotlin',all(('fun '+m) in nb for m in set(re.findall(r'NativeBridge\.(\w+)\(',js))), ', '.join(sorted(m for m in set(re.findall(r'NativeBridge\.(\w+)\(',js)) if ('fun '+m) not in nb)))
chk('html<->bridge: every Native.onX called from Kotlin is defined in JS',all(('Native.'+m) in js or ('Native.'+m) in h for m in set(re.findall(r'call\("(on\w+)"',nb))), ', '.join(sorted(m for m in set(re.findall(r'call\("(on\w+)"',nb)) if ('Native.'+m) not in js)))
refs=set(re.findall(r'(?:src|href)="([^"#:]+\.(?:png|svg|jpg))"',h)); miss=[r for r in refs if not os.path.exists(W+r) and not os.path.exists(os.path.join(R,r))]
chk('local resources referenced by index.html exist (assets or repo root, copied by workflow)',not miss,str(miss))
chk('orb loop protected (try/catch + global extLevel)','extLevel' in h and '}catch(e){}})(0);' in h)
chk('Portuguese format + not-spoken split present',"function splitPt(raw)" in h and "raw.indexOf('===')" in h and 'NativeBridge.translate' in h)

# Speech queue regression checks: exercise the real helper from index.html in Node.
speech_match=re.search(r'function speechText\(s\)\s*\{[\s\S]*?\n\}',h)
chk('speech queue strips Portuguese segment and UI labels',bool(speech_match))
chk('streaming chat is autosaved with debounce and flushed on app hide', 'const schedulePersist=()=>{clearTimeout(persistTimer);persistTimer=setTimeout(()=>{persistTimer=0;persist()},300)};' in h and 'window.addEventListener(\'pagehide\',flushPersist)' in h and "if(document.hidden)flushPersist()" in h and 'schedulePersist();' in h)
chk('chat history is saved before generation and on completion or error', "c.msgs.push({r:'me',t:text})" in h and "c.msgs.push(ai);persist();" in h and 'catch(err){busy=false;if(alive())setState(\'idle\');logErr(\'model\',(err&&err.message)||err);persist();return}' in h and 'persist();if(!alive())return;' in h)
if speech_match:
    test_js = speech_match.group(0) + r"""
const assert = require('assert');
assert.strictEqual(speechText('Hello there. === Olá, tudo bem?'), 'Hello there.');
assert.strictEqual(speechText('English AI: How are you?'), 'How are you?');
assert.strictEqual(speechText('Assistant: I am fine.'), 'I am fine.');
assert.strictEqual(speechText('<b>Hello</b> *there*!'), 'Hello there!');
assert.strictEqual(speechText('   '), '');
assert.strictEqual(speechText('**Hello there!**'), 'Hello there!');
assert.strictEqual(speechText('English IA: Good morning!'), 'Good morning!');
assert.strictEqual(speechText('English response: Welcome back.'), 'Welcome back.');
assert.strictEqual(speechText('Hello there.\n===\nOlá!'), 'Hello there.');
assert.strictEqual(speechText('English AI:   How are you   ?'), 'How are you?');
"""
    open('/tmp/_speech_regression.js','w').write(test_js)
    speech_run=subprocess.run(['node','/tmp/_speech_regression.js'],capture_output=True,text=True)
    chk('speech queue regression tests (Node)',speech_run.returncode==0,speech_run.stderr.strip())

chk('Portuguese fallback remains active in native mode',"ai.pt=S.pt?sp.pt:''" in h and 'Portuguese subtitles fallback' in h and 'Preparing Portuguese subtitles' in h)
chk('Portuguese captions translate progressively while the answer streams',"NativeBridge.translate('live:'+my+':'+idx,sentences[idx])" in h and 'nativeLiveCount' in h)
chk('Portuguese captions restore when app resumes', 'resumePortugueseSupport' in h and "document.addEventListener('visibilitychange'" in h and "window.addEventListener('pageshow'" in h)
# Additional packaging/workflow safeguards (these are static checks, not an Android build).
try:
    manifest=json.load(open(os.path.join(R,'manifest.json'),encoding='utf-8'))
    packaged=json.load(open(os.path.join(W,'manifest.json'),encoding='utf-8'))
    chk('Web manifest valid JSON and packaged in native assets',manifest == packaged and bool(manifest.get('icons')))
except Exception as e:
    chk('Web manifest valid JSON and packaged in native assets',False,str(e))
workflow=rd(os.path.join(R,'.github/workflows/android-native.yml'))
chk('workflow runs static checks before Gradle compile',workflow.find('python3 tools/static_check.py') >= 0 and workflow.find('python3 tools/static_check.py') < workflow.find('gradle assembleDebug'))
chk('workflow verifies APK versionCode 7/versionName 1.3.4',"versionCode='7' versionName='1.3.4'" in workflow)
chk('startup recovers backup after interrupted model import','backupModelFile.renameTo(modelFile)' in nb and 'previous model was restored after an interrupted update' in nb)
print('\nRESULT:','ALL PASSED' if ok else 'FAILURES'); sys.exit(0 if ok else 1)
