import { router } from 'expo-router';
import { Image } from 'react-native';
import React, { useEffect, useState } from 'react';
import { services } from '../backend';
import { useAuth } from '../auth/AuthProvider';
import { Screen, Label, Field, PrimaryButton } from '../components/ui';
import { useTrustGuard } from './useTrustGuard';
import type { SafetyConfiguration, SafetyReport, ModerationAppeal, Verification } from './api';
export default function ModerationScreen() {
  const { session } = useAuth();
  const begin = useTrustGuard(session?.user.id);
  const [config, setConfig] = useState<SafetyConfiguration | null>(null),
    [verification, setVerification] = useState<
      (Verification & { id: string; display_name: string })[]
    >([]),
    [reports, setReports] = useState<SafetyReport[]>([]),
    [appeals, setAppeals] = useState<ModerationAppeal[]>([]),
    [reason, setReason] = useState(''),
    [busy, setBusy] = useState(false),
    [error, setError] = useState('');
  async function load() {
    const valid = begin();
    try {
      const c = await services!.trust.configuration();
      if (!valid()) return;
      setConfig(c);
      const [v, r, a] = await Promise.all([
        c.features.verification && c.reviewer_scopes.includes('verification')
          ? services!.trust.verificationQueue()
          : [],
        c.features.reporting && c.reviewer_scopes.includes('moderation')
          ? services!.trust.moderationQueue()
          : [],
        c.features.reporting && c.reviewer_scopes.includes('moderation')
          ? services!.trust.appealQueue()
          : [],
      ]);
      if (valid()) {
        setVerification(v);
        setReports(r);
        setAppeals(a);
      }
    } catch (e) {
      if (valid()) setError(e instanceof Error ? e.message : 'Queue unavailable.');
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
        setReason('');
        await load();
      }
    } catch (e) {
      if (valid()) setError(e instanceof Error ? e.message : 'Decision failed.');
    } finally {
      if (valid()) setBusy(false);
    }
  }
  return (
    <Screen title="Authorized safety review">
      <Label>
        Only provisioned reviewers can decide cases. Never review yourself, your report, or your own
        prior decision on appeal. Review facts manually; badges and signals do not establish
        identity or safety.
      </Label>
      {!config ? (
        <Label>Loading permissions…</Label>
      ) : !config.reviewer_scopes.length ? (
        <Label>No reviewer access.</Label>
      ) : null}
      <Field
        label="Public decision reason (do not identify reporters or include private evidence)"
        value={reason}
        onChangeText={setReason}
        multiline
        maxLength={1000}
      />
      {error ? <Label accessibilityRole="alert">{error}</Label> : null}
      {config?.reviewer_scopes.includes('disputes') ? (
        <PrimaryButton
          title="Review booking disputes"
          onPress={() => router.push({ pathname: '/safety/disputes', params: { admin: '1' } })}
        />
      ) : null}
      {config?.reviewer_scopes.includes('retention') ? (
        <PrimaryButton
          title="Delete expired image evidence (up to 10 objects)"
          disabled={busy}
          onPress={() =>
            void act(async () => {
              const result = await services!.trust.evidenceCleanup(session!.user.id);
              setError(`${result.deleted} expired private objects deleted.`);
            })
          }
        />
      ) : null}
      <Label>Verification queue</Label>
      {verification.map((item) => (
        <React.Fragment key={item.id}>
          <Label>
            {item.display_name} · {item.status}
          </Label>
          <Label>{item.statement}</Label>
          <VerificationEvidence id={item.id} />
          {['UNDER_REVIEW', 'VERIFIED', 'REJECTED'].map((decision) => (
            <PrimaryButton
              key={decision}
              secondary
              title={decision.replaceAll('_', ' ')}
              disabled={busy || reason.trim().length < 3}
              onPress={() =>
                void act(() =>
                  services!.trust.verificationDecision(item.id, decision, reason, session!.user.id),
                )
              }
            />
          ))}
        </React.Fragment>
      ))}
      <Label>Reports</Label>
      {reports.map((item) => (
        <React.Fragment key={item.id}>
          <Label>
            {item.target_kind} · {item.category}
          </Label>
          <Label>{item.description}</Label>
          {item.target_snapshot?.body ? (
            <Label>Reported content: {String(item.target_snapshot.body)}</Label>
          ) : null}
          {(item.target_kind === 'review'
            ? ['dismissed', 'warning', 'review_hidden']
            : ['dismissed', 'warning']
          ).map((decision) => (
            <PrimaryButton
              key={decision}
              secondary
              title={decision.replaceAll('_', ' ')}
              disabled={busy || reason.trim().length < 3}
              onPress={() =>
                void act(() =>
                  services!.trust.moderationDecision(item.id, decision, reason, session!.user.id),
                )
              }
            />
          ))}
        </React.Fragment>
      ))}
      <Label>Appeals</Label>
      {appeals.map((item) => (
        <React.Fragment key={item.id}>
          <Label>
            {item.statement} · {item.status}
          </Label>
          {['UPHELD', 'OVERTURNED', 'ESCALATED'].map((status) => (
            <PrimaryButton
              key={status}
              secondary
              title={status}
              disabled={busy || reason.trim().length < 3}
              onPress={() =>
                void act(() =>
                  services!.trust.resolveAppeal(item.id, status, reason, session!.user.id),
                )
              }
            />
          ))}
        </React.Fragment>
      ))}
      {!verification.length && !reports.length && !appeals.length ? (
        <Label>No cases in these queues.</Label>
      ) : null}
      <PrimaryButton title="Refresh queues" disabled={busy} onPress={() => void load()} />
    </Screen>
  );
}
function VerificationEvidence({ id }: { id: string }) {
  const { session } = useAuth();
  const begin = useTrustGuard(session?.user.id);
  const [items, setItems] = useState<{ id: string; portfolio_path: string }[]>([]),
    [error, setError] = useState('');
  useEffect(() => {
    const valid = begin();
    void services!.trust
      .verificationEvidence(id)
      .then((rows) => {
        if (valid()) setItems(rows);
      })
      .catch(() => {
        if (valid()) setError('Evidence unavailable.');
      });
  }, [id]); // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <>
      <Label>
        {error || `${items.length} submitted portfolio items (current review access only)`}
      </Label>
      {items.map((item) => (
        <ReviewedPortfolioImage key={item.id} path={item.portfolio_path} />
      ))}
    </>
  );
}

function ReviewedPortfolioImage({ path }: { path: string }) {
  const { session } = useAuth();
  const begin = useTrustGuard(session?.user.id);
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState('');
  async function sign() {
    const valid = begin();
    try {
      const result = await services!.media.signedUrl(path);
      if (valid()) setUrl(result);
    } catch {
      if (valid()) setError('Authorized evidence image unavailable.');
    }
  }
  return (
    <>
      {url ? (
        <Image
          source={{ uri: url }}
          accessibilityLabel="Submitted portfolio evidence"
          style={{ width: '100%', height: 240, resizeMode: 'contain' }}
        />
      ) : null}
      {error ? <Label>{error}</Label> : null}
      <PrimaryButton
        secondary
        title="View submitted portfolio (60-second access)"
        onPress={() => void sign()}
      />
    </>
  );
}
