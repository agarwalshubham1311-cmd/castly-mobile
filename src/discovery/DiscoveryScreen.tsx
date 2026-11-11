import React, { useEffect, useRef, useState } from 'react';
import { Pressable } from 'react-native';
import { router } from 'expo-router';
import { useAuth } from '../auth/AuthProvider';
import { services } from '../backend';
import { Field, Label, PrimaryButton, Screen } from '../components/ui';
import type { CampaignConstraints, DiscoveryCandidate, DiscoveryConfiguration } from './types';

const blank: CampaignConstraints = {
  confirmed: false, skills: [], remote: false, deliverables: [],
  allow_unknown_availability: false, personalize: false, allow_external_ai: false,
  preferred_skills: [], budget_unit: null,
};
const comma = (value: string) => value.split(',').map((s) => s.trim().toLowerCase()).filter(Boolean);
function Toggle({ title, value, onPress }: { title: string; value: boolean; onPress: () => void }) {
  return <Pressable accessibilityRole="checkbox" accessibilityState={{ checked: value }}
    onPress={onPress} style={{ minHeight: 48, justifyContent: 'center' }}>
    <Label>{value ? '✓ ' : ''}{title}</Label>
  </Pressable>;
}
export default function DiscoveryScreen() {
  const { session } = useAuth();
  const actor = session?.user.id;
  const generation = useRef(0);
  const [config, setConfig] = useState<DiscoveryConfiguration | null>(null);
  const [description, setDescription] = useState('');
  const [campaign, setCampaign] = useState<CampaignConstraints>(blank);
  const [skillsText, setSkillsText] = useState('');
  const [preferredText, setPreferredText] = useState('');
  const [deliverablesText, setDeliverablesText] = useState('');
  const [budgetText, setBudgetText] = useState('');
  const [rows, setRows] = useState<DiscoveryCandidate[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [draft, setDraft] = useState('');
  const [comparison, setComparison] = useState('');
  const [method, setMethod] = useState('');
  const [offset, setOffset] = useState(0);
  const [sort, setSort] = useState<'name' | 'rate_asc' | 'rate_desc'>('name');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    generation.current++;
    setRows([]); setSelected([]); setDraft(''); setComparison(''); setCampaign(blank);
    setConfig(null); setDescription(''); setSkillsText(''); setPreferredText('');
    setDeliverablesText(''); setBudgetText(''); setMethod(''); setError(''); setBusy(false); setOffset(0);
    if (!actor || !services) return;
    const current = generation.current;
    void services.discovery.configuration().then((value) => {
      if (current === generation.current) setConfig(value);
    }).catch(() => { if (current === generation.current) setError('Discovery is unavailable. Retry.'); });
    return () => { generation.current++; };
  }, [actor]);
  function change(value: Partial<CampaignConstraints>) {
    generation.current++; setBusy(false); setMethod(''); setOffset(0);
    setCampaign((old) => ({ ...old, ...value, confirmed: false }));
    setRows([]); setSelected([]); setDraft(''); setComparison('');
  }
  async function run(work: () => Promise<void>) {
    if (!actor) return;
    const current = ++generation.current;
    setBusy(true); setError('');
    try { await work(); }
    catch (e) { if (current === generation.current) setError(e instanceof Error ? e.message : 'Request failed. Retry.'); }
    finally { if (current === generation.current) setBusy(false); }
  }
  function cancel() { generation.current++; setBusy(false); setError('Request cancelled on this screen.'); }
  function confirm() {
    generation.current++; setBusy(false); setRows([]); setSelected([]); setDraft(''); setMethod('');
    setCampaign((old) => ({ ...old, confirmed: !old.confirmed }));
  }
  async function parse() {
    if (!actor) return;
    const current = generation.current + 1;
    await run(async () => {
      const result = await services!.discovery.parse(description, actor);
      if (current !== generation.current) return;
      const next = result as Record<string, unknown>;
      const skills = Array.isArray(next.skills) ? next.skills.map(String) : [];
      const deliverables = Array.isArray(next.deliverables) ? next.deliverables.map(String) : [];
      setCampaign({ ...blank, category: typeof next.category === 'string' ? next.category : null,
        city: typeof next.city === 'string' ? next.city : null,
        from: typeof next.from === 'string' ? next.from : null,
        through: typeof next.through === 'string' ? next.through : null,
        max_budget: typeof next.max_budget === 'number' ? next.max_budget : null,
        format: typeof next.format === 'string' ? next.format as CampaignConstraints['format'] : null,
        skills, deliverables });
      setSkillsText(skills.join(', ')); setDeliverablesText(deliverables.join(', '));
      setBudgetText(next.max_budget == null ? '' : String(next.max_budget));
      setError('Review every inferred field and choose the budget unit before confirming.');
    });
  }
  function prepared(): CampaignConstraints {
    return { ...campaign, skills: comma(skillsText), preferred_skills: comma(preferredText),
      deliverables: comma(deliverablesText), max_budget: budgetText ? Number(budgetText) : null };
  }
  async function advanced(nextOffset = 0) {
    const current = generation.current + 1;
    await run(async () => {
      const value = prepared();
      const result = await services!.discovery.advanced({
        category: value.category || '', city: value.remote ? '' : value.city || '',
        region: value.remote ? '' : value.region || '', skills: value.skills.join(','),
        max_rate: value.budget_unit === 'per_day' ? value.max_budget || undefined : undefined,
        from: value.from || undefined, through: value.through || undefined,
        availability: value.from && !value.allow_unknown_availability ? 'confirmed' : 'any',
        sort, limit: 25, offset: nextOffset,
      });
      if (current === generation.current) { setRows(result); setOffset(nextOffset); setMethod('advanced search'); }
    });
  }
  async function shortlist(personalized = false) {
    if (!actor) return;
    const current = generation.current + 1;
    await run(async () => {
      const result = await services!.discovery.shortlist(prepared(), actor, personalized);
      if (current === generation.current) { setRows(result.candidates); setMethod(result.method === 'deterministic' ? 'Deterministic, explainable suggestions' : 'Provider reordered suggestions'); setOffset(0); }
    });
  }
  async function brief(id: string) {
    if (!actor) return;
    const current = generation.current + 1;
    await run(async () => {
      const result = await services!.discovery.brief(prepared(), id, actor);
      if (current === generation.current) setDraft(result.draft);
    });
  }
  async function compare() {
    if (!actor) return;
    const current = generation.current + 1;
    await run(async () => {
      const result = await services!.discovery.compare(selected, actor);
      if (current === generation.current) setComparison(result.items.map((item) =>
        `${item.display_name}: ${item.category_id}, ${item.city}, INR ${item.day_rate}/day indicative, declared skills ${item.skills.join(', ') || 'unknown'}. Availability and final quote need confirmation.`).join('\n'));
    });
  }
  return <Screen title="Campaign discovery">
    <Label>Describe a campaign, then review and confirm the structured fields. Suggestions do not book, message or edit anyone. No external AI provider is configured.</Label>
    {error ? <Label accessibilityRole="alert">{error}</Label> : null}
    {!config ? <Label>Loading discovery features…</Label> : null}
    <Field label="Campaign description" value={description} onChangeText={(v) => { generation.current++; setBusy(false); setDescription(v); }} multiline maxLength={1000} />
    {config?.smart_matching ? <PrimaryButton title="Parse campaign locally" disabled={busy || !description.trim()} onPress={() => void parse()} /> : null}
    <Field label="Creator category (actors, models, creators, musicians, photographers)" value={campaign.category || ''} onChangeText={(v) => change({ category: v.toLowerCase() })} />
    <Field label="Required or preferred skills, comma separated" value={skillsText} onChangeText={(v) => { setSkillsText(v); change({}); }} />
    <Field label="City (public profile label)" value={campaign.city || ''} onChangeText={(v) => change({ city: v })} />
    <Field label="Region (only creator-shared)" value={campaign.region || ''} onChangeText={(v) => change({ region: v })} />
    <Toggle title="Remote work is acceptable" value={campaign.remote} onPress={() => change({ remote: !campaign.remote })} />
    <Field label="Start date (YYYY-MM-DD)" value={campaign.from || ''} onChangeText={(v) => change({ from: v || null })} />
    <Field label="End date (YYYY-MM-DD)" value={campaign.through || ''} onChangeText={(v) => change({ through: v || null })} />
    <Toggle title="Include unknown date availability; I will confirm directly" value={campaign.allow_unknown_availability} onPress={() => change({ allow_unknown_availability: !campaign.allow_unknown_availability })} />
    <Field label="Budget amount in INR (unit must be confirmed)" value={budgetText} onChangeText={(v) => { setBudgetText(v); change({}); }} keyboardType="numeric" />
    <Toggle title="Budget is an indicative per-day rate ceiling" value={campaign.budget_unit === 'per_day'} onPress={() => change({ budget_unit: 'per_day' })} />
    <Toggle title="Budget is a total campaign amount; final quote unknown" value={campaign.budget_unit === 'campaign_total'} onPress={() => change({ budget_unit: 'campaign_total' })} />
    <Field label="Deliverables, comma separated (reel, video, photos, post, performance)" value={deliverablesText} onChangeText={(v) => { setDeliverablesText(v); change({}); }} />
    <Toggle title="I confirm these structured campaign fields" value={campaign.confirmed} onPress={confirm} />
    <Toggle title="Opt in to personalized ranking from my selected skills" value={campaign.personalize} onPress={() => { if (campaign.personalize && actor) void services?.discovery.forget(actor); change({ personalize: !campaign.personalize, allow_external_ai: false }); }} />
    {campaign.personalize ? <Field label="My preferred skills, comma separated" value={preferredText} onChangeText={(v) => { setPreferredText(v); change({}); }} /> : null}
    {config?.external_provider ? <Toggle title="Separately allow external AI processing" value={campaign.allow_external_ai} onPress={() => { if (campaign.allow_external_ai && actor) void services?.discovery.forget(actor); change({ allow_external_ai: !campaign.allow_external_ai }); }} /> : null}
    {config?.advanced_search ? <>
      <PrimaryButton title="Advanced search" disabled={busy} onPress={() => void advanced()} />
      <PrimaryButton title="Sort by indicative day rate" disabled={busy} onPress={() => { generation.current++; setBusy(false); setRows([]); setMethod(''); setSort(sort === 'rate_asc' ? 'rate_desc' : 'rate_asc'); }} />
      <PrimaryButton title="Previous results" disabled={busy || offset === 0} onPress={() => void advanced(Math.max(0, offset - 25))} />
      <PrimaryButton title="More results" disabled={busy || rows.length < 25} onPress={() => void advanced(offset + 25)} />
    </> : null}
    {config?.smart_matching ? <PrimaryButton title="Build confirmed shortlist" disabled={busy || !campaign.confirmed} onPress={() => void shortlist()} /> : null}
    {config?.ai_recommendations ? <PrimaryButton title="Personalized recommendations" disabled={busy || !campaign.confirmed || !campaign.personalize} onPress={() => void shortlist(true)} /> : null}
    {busy ? <PrimaryButton title="Cancel this request" onPress={cancel} /> : null}
    {method ? <Label>{method}. {rows.length} creator results. Indicative rates are per day; final project quotes are unknown.</Label> : null}
    {!busy && method && rows.length === 0 ? <Label>No matches. Edit a filter or allow clearly labeled unknown availability.</Label> : null}
    {rows.map((row) => <React.Fragment key={row.id}>
      <Label>{row.display_name} · {row.category_id} · {row.city} · INR {row.day_rate}/day indicative</Label>
      <Label>Declared skills: {row.skills.join(', ') || 'unknown'} · Date availability: {row.availability_status}</Label>
      {row.reasons?.map((reason) => <Label key={reason}>Why matched: {reason}</Label>)}
      {row.missing_data?.map((missing) => <Label key={missing}>Needs confirmation: {missing}</Label>)}
      <PrimaryButton secondary title={`Open ${row.display_name} profile`} onPress={() => router.push(`/talent/${row.id}`)} />
      {config?.ai_assistance && campaign.confirmed ? <PrimaryButton secondary title={`Draft brief for ${row.display_name}`} disabled={busy} onPress={() => void brief(row.id)} /> : null}
      {config?.ai_assistance ? <Toggle title={`Compare ${row.display_name}`} value={selected.includes(row.id)} onPress={() => setSelected((old) => old.includes(row.id) ? old.filter((id) => id !== row.id) : old.length < 3 ? [...old, row.id] : old)} /> : null}
    </React.Fragment>)}
    {selected.length && config?.ai_assistance ? <PrimaryButton title="Compare selected creators" disabled={busy} onPress={() => void compare()} /> : null}
    {comparison ? <Label>{comparison}</Label> : null}
    {draft ? <Field label="Editable booking brief suggestion (not sent)" value={draft} onChangeText={setDraft} multiline maxLength={4000} /> : null}
  </Screen>;
}
