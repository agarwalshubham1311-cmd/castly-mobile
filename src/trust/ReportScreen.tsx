import React, { useState } from 'react';
import { useLocalSearchParams } from 'expo-router';
import { services } from '../backend';
import { useAuth } from '../auth/AuthProvider';
import { Screen, Label, Field, PrimaryButton } from '../components/ui';
import { useTrustGuard } from './useTrustGuard';
export default function ReportScreen() {
  const { kind, id } = useLocalSearchParams<{ kind: string; id: string }>();
  const { session } = useAuth();
  const begin = useTrustGuard(session?.user.id);
  const [category, setCategory] = useState('other'),
    [description, setDescription] = useState(''),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState('');
  async function submit() {
    const valid = begin();
    setBusy(true);
    try {
      const result = await services!.trust.report(
        kind,
        id,
        category,
        description,
        session!.user.id,
      );
      if (valid())
        setMessage(
          `Report ${result.id} is ${result.status.toLowerCase()}. Authorized moderators can review the reported content.`,
        );
    } catch (e) {
      if (valid()) setMessage(e instanceof Error ? e.message : 'Report failed.');
    } finally {
      if (valid()) setBusy(false);
    }
  }
  return (
    <Screen title="Report a safety concern">
      <Label>
        Report this {kind}. Your description and the relevant content are shared with authorized
        moderators. Do not include government IDs, payment credentials or unrelated private
        information. For immediate danger, contact local emergency services.
      </Label>
      {['harassment', 'spam', 'impersonation', 'fraud', 'inappropriate_content', 'other'].map(
        (value) => (
          <PrimaryButton
            key={value}
            secondary
            title={`${category === value ? 'Selected: ' : ''}${value.replaceAll('_', ' ')}`}
            onPress={() => setCategory(value)}
          />
        ),
      )}
      <Field
        label="What happened?"
        value={description}
        onChangeText={setDescription}
        multiline
        maxLength={2000}
      />
      <PrimaryButton
        title={busy ? 'Submitting…' : 'Submit report'}
        disabled={
          busy ||
          description.trim().length < 10 ||
          !['user', 'portfolio', 'message', 'review'].includes(kind) ||
          !id
        }
        onPress={() => void submit()}
      />
      {message ? <Label accessibilityRole="alert">{message}</Label> : null}
    </Screen>
  );
}
