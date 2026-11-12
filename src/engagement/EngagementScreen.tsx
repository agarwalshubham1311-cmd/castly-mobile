import { router } from 'expo-router';
import type { DiscoveryCandidate } from '../discovery/types';
import React, { useEffect, useState } from 'react';
import { Pressable } from 'react-native';
import { services } from '../backend';
import { useAuth } from '../auth/AuthProvider';
import { Screen, Label, Field, PrimaryButton } from '../components/ui';
import { readableError } from '../backend/errors';
import type { EngagementConfiguration, Preferences, SavedSearch } from './types';
import { defaultPreferences, preferenceInput } from './types';
import { registerPush, unregisterPush } from './push';
function Toggle({ title, value, onPress }: { title: string; value: boolean; onPress(): void }) { return <Pressable accessibilityRole="switch" accessibilityLabel={title} accessibilityState={{ checked: value }} onPress={onPress} style={{ minHeight: 48, paddingVertical: 12 }}><Label>{title}: {value ? 'On' : 'Off'}</Label></Pressable>; }
export default function EngagementScreen() { const auth = useAuth(); return auth.session?.user.id ? <Scoped key={auth.session.user.id} actor={auth.session.user.id} /> : <Screen title="Notifications & engagement"><Label>Sign in to manage live engagement preferences. Mock mode does not send push or email.</Label></Screen>; }
function Scoped({ actor }: { actor?: string }) {
  const [matches,setMatches]=useState<DiscoveryCandidate[]>([]);
  const [matchedSearch,setMatchedSearch]=useState('');
  const [config, setConfig] = useState<EngagementConfiguration>();
  const [prefs, setPrefs] = useState<Preferences>({ ...defaultPreferences });
  const [saved, setSaved] = useState<SavedSearch[]>([]), [error, setError] = useState(''), [notice, setNotice] = useState(''), [busy, setBusy] = useState(false);
  const [label, setLabel] = useState(''), [city, setCity] = useState(''), [category, setCategory] = useState(''), [skills, setSkills] = useState(''), [budget, setBudget] = useState(''), [region, setRegion] = useState(''), [ahead, setAhead] = useState(''), [window, setWindow] = useState('1');
  const [includeUnknown, setIncludeUnknown] = useState(false);
  const [alerts, setAlerts] = useState(false), [confirmed, setConfirmed] = useState(false), [exported, setExported] = useState(''), [revision, setRevision] = useState(0);
  useEffect(() => { if (!actor || !services) return; let active = true; void services.engagement.configuration().then(async (c) => { if (!active) return; setConfig(c); if (Object.values(c).some(Boolean)) { const p = await services!.engagement.preferences(actor); const searches = c.engagement ? await services!.engagement.saved(actor) : []; if (active) { setPrefs(preferenceInput(p)); setSaved(searches); } } }).catch((e) => { if (active) setError(readableError(e)); }); return () => { active = false; }; }, [actor, revision]);
  async function run(work: () => Promise<unknown>, message: string) { if (!actor || busy) return; setBusy(true); setError(''); setNotice(''); try { await work(); setNotice(message); } catch (e) { setError(readableError(e)); } finally { setBusy(false); } }
  function toggle(key: keyof Preferences) { setPrefs((old) => ({ ...old, [key]: !old[key] })); }
  return <Screen title="Notifications & engagement">
    <Label>Choose each channel separately. Push is best effort; private details stay inside Castly. Optional alerts and analytics start off.</Label>
    {!config ? <Label>Loading engagement configuration…</Label> : !Object.values(config).some(Boolean) ? <Label>Engagement features are not enabled. In-app notifications remain available.</Label> : <>
      <Field label="IANA timezone" value={prefs.timezone} maxLength={80} onChangeText={(timezone) => setPrefs({ ...prefs, timezone })} />
      <Field label="Quiet hours start (0–23)" value={String(prefs.quiet_start)} keyboardType="numeric" onChangeText={(v) => setPrefs({ ...prefs, quiet_start: Number(v) })} />
      <Field label="Quiet hours end (0–23; equal start disables quiet hours)" value={String(prefs.quiet_end)} keyboardType="numeric" onChangeText={(v) => setPrefs({ ...prefs, quiet_end: Number(v) })} />
      {config.push ? <><Toggle title="Booking push notifications" value={prefs.push_bookings} onPress={() => toggle('push_bookings')} /><Toggle title="Message push notifications" value={prefs.push_messages} onPress={() => toggle('push_messages')} /></> : null}
      {config.email ? <><Toggle title="Booking and account email (transactional)" value={prefs.email_transactional} onPress={() => toggle('email_transactional')} /><Toggle title="Optional email digests and reminders" value={prefs.email_optional} onPress={() => toggle('email_optional')} /><PrimaryButton title="Verify my email with Supabase" disabled={busy} onPress={() => void run(() => services!.engagement.verifyEmail(actor!), 'Verified email synchronized. No email was sent.')} /></> : null}
      {config.engagement ? <><Toggle title="Booking reminders" value={prefs.reminders} onPress={() => toggle('reminders')} /><Toggle title="Saved search alerts" value={prefs.saved_alerts} onPress={() => toggle('saved_alerts')} /><Toggle title="Occasional re-engagement" value={prefs.reengagement} onPress={() => toggle('reengagement')} /></> : null}
      {config.analytics ? <Toggle title="Privacy-minimized product analytics" value={prefs.analytics} onPress={() => toggle('analytics')} /> : null}
      <PrimaryButton title="Save notification preferences" disabled={busy} onPress={() => void run(async () => { await services!.engagement.update(actor!, prefs); setRevision((r) => r + 1); }, 'Preferences saved. Channel and provider rollout settings still apply.')} />
      {config.push ? <><PrimaryButton title="Enable notifications on this device" disabled={busy} onPress={() => void run(() => registerPush(actor!, true), 'Device registered with your saved push preferences.')} /><PrimaryButton secondary title="Remove this device" disabled={busy} onPress={() => void run(() => unregisterPush(actor!), 'Device registration removed.')} /></> : null}
      {!config.push_delivery && !config.email_delivery ? <Label>External delivery providers are disabled. No push or email will be sent.</Label> : null}
      {config.engagement ? <>
        <Label>Saved searches use public, eligible creators and exclude blocked or unpublished profiles. Dates without creator declarations remain unknown. Digests need both saved-search alerts and an opted-in channel.</Label>
        <Field label="Saved search name" value={label} onChangeText={setLabel} maxLength={80} />
        <Field label="Creator category" value={category} onChangeText={setCategory} maxLength={30} />
        <Field label="City" value={city} onChangeText={setCity} maxLength={100} />
        <Field label="Creator-shared region" value={region} onChangeText={setRegion} maxLength={100} />
        <Field label="Skills, comma separated" value={skills} onChangeText={setSkills} maxLength={200} />
        <Field label="Maximum indicative day rate (INR)" value={budget} onChangeText={setBudget} keyboardType="numeric" maxLength={8} />
        <Field label="Days ahead (optional, 0–30)" value={ahead} onChangeText={setAhead} keyboardType="numeric" maxLength={2} />
        <Field label="Date window days (1–7)" value={window} onChangeText={setWindow} keyboardType="numeric" maxLength={1} />
        <Toggle title="Include unknown availability in dated searches" value={includeUnknown} onPress={() => setIncludeUnknown(!includeUnknown)} />
        <Toggle title="Alerts for this saved search" value={alerts} onPress={() => setAlerts(!alerts)} />
        <PrimaryButton title="Save search" disabled={busy || !label.trim()} onPress={() => void run(async () => { await services!.engagement.save(actor!, label, { category: category || undefined, city: city || undefined, region: region || undefined, skills: skills.split(',').map((s) => s.trim().toLowerCase()).filter(Boolean), max_rate: budget ? Number(budget) : null, days_ahead: ahead ? Number(ahead) : null, window_days: Number(window), include_unknown: includeUnknown }, alerts); setRevision((r) => r + 1); }, 'Search saved. No external message was sent.')} />
        {saved.length === 0 ? <Label>No saved searches yet.</Label> : saved.map((s) => <React.Fragment key={s.id}><Label>{s.label} · alerts {s.alerts ? 'on' : 'off'}</Label><PrimaryButton secondary title={`Run saved search ${s.label}`} disabled={busy} onPress={()=>void run(async()=>{const found=await services!.engagement.results(actor!,s.id);setMatches(found);setMatchedSearch(s.label);},'Current public matches loaded.')} /><PrimaryButton secondary title={`${s.alerts ? 'Pause' : 'Enable'} alerts for ${s.label}`} disabled={busy} onPress={() => void run(async () => { await services!.engagement.save(actor!, s.label, typeof s.filters === 'string' ? JSON.parse(s.filters) : s.filters, !s.alerts, s.id); setRevision((r) => r + 1); }, 'Saved-search alert preference updated.')} /><PrimaryButton secondary title={`Delete saved search ${s.label}`} disabled={busy} onPress={() => void run(async () => { await services!.engagement.remove(actor!, s.id); setRevision((r) => r + 1); }, 'Saved search deleted.')} /></React.Fragment>)}
      </> : null}
      {matchedSearch ? <><Label>Matches for {matchedSearch}: {matches.length}. Availability and final rates need confirmation.</Label>{matches.map((m)=><React.Fragment key={m.id}><Label>{m.display_name} · {m.category_id} · {m.city} · INR {m.day_rate}/day · availability {m.availability_status}</Label><PrimaryButton secondary title={`View matching profile ${m.display_name}`} onPress={()=>router.push(`/talent/${m.id}`)}/></React.Fragment>)}</>:null}
      <PrimaryButton secondary title="Export my engagement data" disabled={busy} onPress={() => void run(async () => setExported(JSON.stringify(await services!.engagement.export(actor!), null, 2)), 'Your data is shown below; it is not uploaded or copied automatically.')} />
      <Toggle title="Confirm deletion of my engagement data and opt-ins" value={confirmed} onPress={() => setConfirmed(!confirmed)} />
      <PrimaryButton secondary title="Delete my engagement data" disabled={busy || !confirmed} onPress={() => void run(async () => { await services!.engagement.delete(actor!); setPrefs({ ...defaultPreferences }); setSaved([]); setMatches([]); setMatchedSearch(''); setExported(''); setConfirmed(false); }, 'Engagement data and opt-ins deleted. Your account, bookings and messages remain.')} />
      {config.analytics ? <PrimaryButton secondary title="Load aggregate analytics (admin only)" disabled={busy} onPress={() => void run(async () => setExported(JSON.stringify(await services!.engagement.analytics(actor!), null, 2)), 'Authorized aggregate dashboard loaded. Small cells are suppressed.')} /> : null}
      {exported ? <Field label="Engagement data export or authorized aggregates" multiline editable={false} value={exported} /> : null}
    </>}
    {busy ? <Label>Working…</Label> : null}{error ? <Label>{error}</Label> : null}{notice ? <Label>{notice}</Label> : null}
    <PrimaryButton secondary title="Refresh engagement settings" disabled={busy} onPress={() => setRevision((r) => r + 1)} />
  </Screen>;
}
