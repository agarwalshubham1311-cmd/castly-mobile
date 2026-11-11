import React, { useEffect, useRef, useState } from 'react';
import { Pressable } from 'react-native';
import { useAuth } from '../auth/AuthProvider';
import { services } from '../backend';
import { Field, Label, PrimaryButton, Screen } from '../components/ui';
import type { DiscoveryConfiguration } from './types';
export default function CreatorDiscoveryScreen() {
  const { session } = useAuth();
  const actor = session?.user.id;
  const generation = useRef(0);
  const [config, setConfig] = useState<DiscoveryConfiguration | null>(null);
  const [region, setRegion] = useState('');
  const [share, setShare] = useState(false);
  const [day, setDay] = useState('');
  const [available, setAvailable] = useState(true);
  const [tips, setTips] = useState<string[]>([]);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const current = ++generation.current;
    setRegion(''); setDay(''); setTips([]); setShare(false); setMessage(''); setConfig(null); setBusy(false);
    if (actor && services) void services.discovery.configuration().then((value) => {
      if (current === generation.current) setConfig(value);
    }).catch(() => { if (current === generation.current) setMessage('Discovery controls are unavailable.'); });
    return () => { generation.current++; };
  }, [actor]);
  async function run(task: () => Promise<string>) {
    if (!actor) return;
    const current = ++generation.current;
    setBusy(true); setMessage('');
    try { const result = await task(); if (current === generation.current) setMessage(result); }
    catch (e) { if (current === generation.current) setMessage(e instanceof Error ? e.message : 'Retry the action.'); }
    finally { if (current === generation.current) setBusy(false); }
  }
  return <Screen title="Creator discovery controls">
    <Label>Share only a broad region if you choose. Your public city already appears on your profile. Date availability is a declaration, not a booking guarantee; private booking details are never shown to searchers.</Label>
    {message ? <Label accessibilityRole="alert">{message}</Label> : null}
    {!config ? <Label>Loading controls…</Label> : null}
    {config?.advanced_search ? <>
      <Field label="Broad region (no coordinates or address)" value={region} onChangeText={setRegion} maxLength={100} />
      <Pressable accessibilityRole="checkbox" accessibilityState={{ checked: share }} onPress={() => setShare(!share)} style={{ minHeight: 48, justifyContent: 'center' }}>
        <Label>{share ? '✓ ' : ''}Share this region in discovery</Label>
      </Pressable>
      <PrimaryButton title="Save region choice" disabled={busy || share && !region.trim()} onPress={() => void run(async () => { await services!.discovery.preference(region.trim(), share, actor!); return 'Region choice saved.'; })} />
      <Field label="Availability day (YYYY-MM-DD)" value={day} onChangeText={setDay} />
      <Pressable accessibilityRole="checkbox" accessibilityState={{ checked: available }} onPress={() => setAvailable(!available)} style={{ minHeight: 48, justifyContent: 'center' }}>
        <Label>{available ? '✓ ' : ''}I declare this date available for inquiry</Label>
      </Pressable>
      <PrimaryButton title="Save date declaration" disabled={busy || !day} onPress={() => void run(async () => { await services!.discovery.availability(day, available, actor!); return 'Date declaration saved. Clients still need direct confirmation.'; })} />
    </> : null}
    {config?.ai_assistance ? <PrimaryButton title="Suggest profile improvements" disabled={busy} onPress={() => void run(async () => { const result = await services!.discovery.profile(actor!); setTips(result.suggestions); return 'Local suggestions only. Nothing was edited.'; })} /> : null}
    {tips.map((tip) => <Label key={tip}>Suggestion: {tip}</Label>)}
    {busy ? <PrimaryButton title="Cancel this request" onPress={() => { generation.current++; setBusy(false); setMessage('Request cancelled on this screen.'); }} /> : null}
  </Screen>;
}
