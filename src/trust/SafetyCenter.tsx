import React, { useEffect, useState } from 'react';
import { router } from 'expo-router';
import { services } from '../backend';
import { useAuth } from '../auth/AuthProvider';
import { Screen, Label, Field, PrimaryButton } from '../components/ui';
import { useTrustGuard } from './useTrustGuard';
import type { SafetyConfiguration, SafetyReport, ModerationAction, ModerationAppeal } from './api';
export default function SafetyCenter() {
  const { session } = useAuth();
  const begin = useTrustGuard(session?.user.id);
  const [config, setConfig] = useState<SafetyConfiguration | null>(null),
    [reports, setReports] = useState<SafetyReport[]>([]),
    [actions, setActions] = useState<ModerationAction[]>([]),
    [appeals, setAppeals] = useState<ModerationAppeal[]>([]),
    [blocks, setBlocks] = useState<{ blocked_id: string }[]>([]),
    [statement, setStatement] = useState(''),
    [selected, setSelected] = useState<string | null>(null),
    [error, setError] = useState(''),
    [busy, setBusy] = useState(false);
  async function load() {
    const valid = begin();
    try {
      const next = await services!.trust.configuration();
      if (!valid()) return;
      setConfig(next);
      if (next.features.reporting) {
        const [r, a, b, p] = await Promise.all([
          services!.trust.reports(),
          services!.trust.actions(),
          services!.trust.blocks(),
          services!.trust.appeals(),
        ]);
        if (valid()) {
          setReports(r);
          setActions(a);
          setBlocks(b);
          setAppeals(p);
        }
      }
    } catch (e) {
      if (valid()) setError(e instanceof Error ? e.message : 'Safety services unavailable.');
    }
  }
  useEffect(() => {
    void Promise.resolve().then(load);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
  async function act(task: () => Promise<unknown>) {
    const valid = begin();
    setBusy(true);
    setError('');
    try {
      await task();
      if (valid()) {
        setSelected(null);
        setStatement('');
        await load();
      }
    } catch (e) {
      if (valid()) setError(e instanceof Error ? e.message : 'Action failed.');
    } finally {
      if (valid()) setBusy(false);
    }
  }
  return (
    <Screen title="Safety Center">
      <Label>
        Agree project scope and meeting details in advance. Meet in appropriate professional
        settings, tell someone you trust about plans, and keep records of your agreement. Never
        share passwords, payment credentials, government IDs or intimate content here.
      </Label>
      <Label>
        Castly does not process booking payments, hold escrow, guarantee payment, or guarantee
        anyone’s safety. Verification covers reviewed profile facts and submitted work. Contact
        local emergency services for immediate danger.
      </Label>
      {error ? <Label accessibilityRole="alert">{error}</Label> : null}
      {config?.features.verification ? (
        <PrimaryButton
          title="Creator verification"
          onPress={() => router.push('/safety/verification')}
        />
      ) : null}
      {config?.reviewer_scopes.length ? (
        <PrimaryButton
          title="Authorized reviewer dashboard"
          onPress={() => router.push('/safety/moderation')}
        />
      ) : null}
      {config?.features.disputes ? (
        <PrimaryButton
          title="My booking disputes"
          onPress={() => router.push('/safety/disputes')}
        />
      ) : null}
      {config?.features.reporting ? (
        <>
          <Label>
            Report concerns using Report on a profile, message, portfolio item or review. Blocking
            prevents new bookings/messages in both directions while preserving existing records and
            dispute access.
          </Label>
          {blocks.map((block) => (
            <PrimaryButton
              key={block.blocked_id}
              secondary
              title={`Unblock account ${block.blocked_id.slice(0, 8)}…`}
              disabled={busy}
              onPress={() =>
                void act(() => services!.trust.block(block.blocked_id, false, session!.user.id))
              }
            />
          ))}
          {!blocks.length ? <Label>No accounts blocked by you.</Label> : null}
          <Label>Your reports</Label>
          {reports.map((report) => (
            <React.Fragment key={report.id}>
              <Label>
                {report.category.replaceAll('_', ' ')} · {report.status}{' '}
                {report.decision ? `· ${report.decision}` : ''}
              </Label>
              {report.reason ? <Label>{report.reason}</Label> : null}
              {report.action_id ? (
                <PrimaryButton
                  secondary
                  title="Appeal report decision"
                  onPress={() => setSelected(report.action_id!)}
                />
              ) : null}
            </React.Fragment>
          ))}
          {actions.map((action) => (
            <React.Fragment key={action.id}>
              <Label>
                {action.decision} · {action.reason} ({action.policy_version})
              </Label>
              <PrimaryButton
                secondary
                title="Appeal this action"
                onPress={() => setSelected(action.id)}
              />
            </React.Fragment>
          ))}
          {selected ? (
            <>
              <Field
                label="Explain your appeal"
                value={statement}
                onChangeText={setStatement}
                multiline
                maxLength={2000}
              />
              <PrimaryButton
                title="Submit appeal"
                disabled={busy || statement.trim().length < 10}
                onPress={() =>
                  void act(() => services!.trust.appeal(selected, statement, session!.user.id))
                }
              />
            </>
          ) : null}
          {appeals.map((appeal) => (
            <Label key={appeal.id}>
              Appeal: {appeal.status} {appeal.resolution || ''}
            </Label>
          ))}
        </>
      ) : (
        <Label>Reporting and blocking are not enabled in this environment yet.</Label>
      )}
      <PrimaryButton
        secondary
        title="Refresh safety status"
        disabled={busy}
        onPress={() => void load()}
      />
    </Screen>
  );
}
