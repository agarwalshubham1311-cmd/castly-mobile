import React, { useEffect, useState } from 'react';
import { Text, View } from 'react-native';
import * as Crypto from 'expo-crypto';
import { router } from 'expo-router';
import { useAuth } from '../auth/AuthProvider';
import { services } from '../backend';
import { c, f } from '../theme';
import type { AccessStatus, Appeal } from '../backend/enforcement';
import { readableError } from '../backend/errors';
import { Screen, Heading, Label, Field, PrimaryButton } from '../components/ui';
const body = { fontFamily: f.regular, color: c.text, fontSize: 14, lineHeight: 22 };
export default function AccountAccess() {
  const auth = useAuth(),
    actor = auth.session?.user.id;
  const [snapshot, setSnapshot] = useState<{
      actor: string | undefined;
      data: AccessStatus;
    } | null>(null),
    [error, setError] = useState(''),
    [busy, setBusy] = useState(false);
  const [statement, setStatement] = useState(''),
    [evidence, setEvidence] = useState(''),
    [decision, setDecision] = useState('');
  const [withdraw, setWithdraw] = useState<{ actor: string | undefined; appeal: Appeal } | null>(
    null,
  );
  const state = snapshot?.actor === actor ? snapshot?.data : null;
  const refresh = () =>
    services!.accountAccess
      .current()
      .then((data) => setSnapshot({ actor, data }))
      .catch((e) => setError(readableError(e)));
  useEffect(() => {
    let active = true;
    services?.accountAccess
      .current()
      .then((s) => {
        if (active) setSnapshot({ actor, data: s });
      })
      .catch((e) => {
        if (active) setError(readableError(e));
      });
    return () => {
      active = false;
    };
  }, [actor]);
  async function submit(
    operation: 'APPEAL_SUBMIT' | 'APPEAL_WITHDRAW' | 'APPEAL_REOPEN_REQUEST',
    payload: Record<string, string | number>,
  ) {
    if (!actor || busy) return;
    setBusy(true);
    setError('');
    try {
      await services!.accountAccess.appeal(
        { request_key: Crypto.randomUUID(), operation, payload },
        actor,
      );
      await refresh();
      setStatement('');
      setEvidence('');
    } catch (e) {
      setError(readableError(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Screen title="Account access">
      {error ? (
        <Text style={[body, { color: c.error }]} accessibilityRole="alert">
          {error}
        </Text>
      ) : null}
      {!state ? (
        <PrimaryButton title="Retry account access" onPress={() => void refresh()} />
      ) : (
        <>
          <View accessibilityRole="header">
            <Heading>{state.state.replaceAll('_', ' ')}</Heading>
          </View>
          <Text style={body}>
            {state.explanation || 'Your account has no active marketplace restrictions.'}
          </Text>
          {state.until_at && (
            <Label>Temporary decision expires {new Date(state.until_at).toLocaleString()}</Label>
          )}
          <Text style={body}>
            Your existing bookings, membership management, privacy and security access remain
            available. An appeal does not automatically remove restrictions.
          </Text>
          <PrimaryButton
            title="View existing bookings"
            onPress={() => router.push('/(tabs)/bookings')}
          />
          <PrimaryButton title="Manage membership" onPress={() => router.push('/subscriptions')} />
          <PrimaryButton title="Support and disputes" onPress={() => router.push('/safety')} />
          <PrimaryButton
            title="Privacy and security"
            onPress={() => router.push('/settings/account')}
          />
          {state.decisions?.map((d) => (
            <View key={d.id} style={{ gap: 8 }}>
              <Heading>{d.state.replaceAll('_', ' ')}</Heading>
              <Text style={body}>{d.explanation}</Text>
              <Label>Notified {new Date(d.notified_at).toLocaleString()}</Label>
              <PrimaryButton title="Choose decision for appeal" onPress={() => setDecision(d.id)} />
            </View>
          ))}
          {decision && (
            <View style={{ gap: 12 }}>
              <Heading>Appeal selected decision</Heading>
              <Label>
                Submit within 30 days of notification. Target review: seven calendar days.
              </Label>
              <Field
                label="Appeal statement"
                value={statement}
                onChangeText={setStatement}
                multiline
                maxLength={4000}
              />
              <Field
                label="Evidence reference (do not paste private documents)"
                value={evidence}
                onChangeText={setEvidence}
                maxLength={500}
              />
              <PrimaryButton
                title={busy ? 'Submitting…' : 'Submit appeal'}
                disabled={busy || statement.trim().length < 20 || evidence.trim().length < 3}
                onPress={() =>
                  void submit('APPEAL_SUBMIT', {
                    decision_id: decision,
                    statement,
                    evidence_ref: evidence,
                  })
                }
              />
            </View>
          )}
          {state.appeals?.map((a) => (
            <View key={a.id} style={{ gap: 10 }}>
              <Heading>Appeal · {a.status.replaceAll('_', ' ')}</Heading>
              <Label>
                Review target {new Date(a.due_at).toLocaleString()}
                {a.overdue ? ' · Overdue, requires escalation' : ''}
              </Label>
              <Text style={body}>{a.resolution || 'No decision yet.'}</Text>
              {['SUBMITTED', 'UNDER_REVIEW'].includes(a.status) ? (
                <PrimaryButton
                  title="Withdraw appeal"
                  disabled={busy}
                  onPress={() => setWithdraw({ actor, appeal: a })}
                />
              ) : (
                <>
                  <Text style={body}>
                    Significant new evidence may justify independent reopening. Complete the
                    statement and evidence fields above.
                  </Text>
                  <PrimaryButton
                    title="Request reopening with new evidence"
                    disabled={busy || statement.trim().length < 20 || evidence.trim().length < 3}
                    onPress={() =>
                      void submit('APPEAL_REOPEN_REQUEST', {
                        appeal_id: a.id,
                        appeal_version: a.version,
                        statement,
                        evidence_ref: evidence,
                      })
                    }
                  />
                </>
              )}
            </View>
          ))}
        </>
      )}
      {withdraw?.actor === actor && withdraw && (
        <View accessibilityRole="alert" style={{ gap: 12 }}>
          <Heading>Confirm appeal withdrawal</Heading>
          <Text style={body}>
            Withdrawal closes this appeal. Reopening requires significant new evidence and an
            independent review.
          </Text>
          <PrimaryButton
            title="Confirm withdrawal"
            disabled={busy}
            onPress={() =>
              void submit('APPEAL_WITHDRAW', {
                appeal_id: withdraw.appeal.id,
                appeal_version: withdraw.appeal.version,
              }).then(() => setWithdraw(null))
            }
          />
          <PrimaryButton
            title="Keep my appeal open"
            disabled={busy}
            onPress={() => setWithdraw(null)}
          />
        </View>
      )}
    </Screen>
  );
}
