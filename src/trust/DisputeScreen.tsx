import React, { useEffect, useState } from 'react';
import { Image, Pressable } from 'react-native';
import { useLocalSearchParams } from 'expo-router';
import { useAuth } from '../auth/AuthProvider';
import { services } from '../backend';
import { Screen, Label, Field, PrimaryButton } from '../components/ui';
import { useTrustGuard } from './useTrustGuard';
import type { DisputeDetails, EvidenceConfiguration } from './api';
export default function DisputeScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { session } = useAuth();
  const begin = useTrustGuard(session?.user.id);
  const [details, setDetails] = useState<DisputeDetails | null>(null),
    [configuration, setConfiguration] = useState<EvidenceConfiguration>({ enabled: false }),
    [reviewer, setReviewer] = useState(false),
    [text, setText] = useState(''),
    [consentedVersion, setConsentedVersion] = useState<string | null>(null),
    [urls, setUrls] = useState<Record<string, string>>({}),
    [error, setError] = useState(''),
    [busy, setBusy] = useState(false);
  const consent = !!configuration.version && consentedVersion === configuration.version;
  const owner = details?.dispute.owner_id === session?.user.id;
  async function load() {
    const valid = begin();
    try {
      const [d, c, roles] = await Promise.all([
        services!.trust.dispute(id),
        services!.trust.evidenceConfiguration(),
        services!.trust.configuration(),
      ]);
      if (valid()) {
        setDetails(d);
        setConsentedVersion(null);
        setConfiguration(c);
        setReviewer(roles.reviewer_scopes.includes('disputes'));
      }
    } catch (e) {
      if (valid()) setError(e instanceof Error ? e.message : 'Dispute unavailable.');
    }
  }
  useEffect(() => {
    void Promise.resolve().then(load);
  }, [id]); // eslint-disable-line react-hooks/exhaustive-deps
  async function act(task: () => Promise<unknown>) {
    const valid = begin();
    setBusy(true);
    setError('');
    try {
      await task();
      if (valid()) {
        setText('');
        setConsentedVersion(null);
        setUrls({});
        await load();
      }
    } catch (e) {
      if (valid()) setError(e instanceof Error ? e.message : 'Action failed.');
    } finally {
      if (valid()) setBusy(false);
    }
  }
  async function upload() {
    const valid = begin();
    setBusy(true);
    setError('');
    try {
      const picker = await import('expo-image-picker');
      if (!valid()) return;
      const selected = await picker.launchImageLibraryAsync({
        mediaTypes: ['images'],
        base64: true,
        exif: false,
        allowsMultipleSelection: false,
        quality: 0.9,
      });
      if (!valid() || selected.canceled) return;
      const image = selected.assets[0];
      if (
        !image.base64 ||
        image.base64.length > 7000000 ||
        (image.fileSize !== undefined && image.fileSize > (configuration.max_bytes || 5242880))
      )
        throw new Error('Choose a JPEG or PNG image under the approved size limit.');
      await services!.trust.evidenceUpload(
        id,
        image.base64,
        configuration.version!,
        session!.user.id,
      );
      if (valid()) {
        setConsentedVersion(null);
        await load();
      }
    } catch (e) {
      if (valid()) setError(e instanceof Error ? e.message : 'Private image upload failed.');
    } finally {
      if (valid()) setBusy(false);
    }
  }
  async function view(evidenceId: string) {
    const valid = begin();
    try {
      const signed = await services!.trust.evidenceUrl(id, evidenceId);
      if (valid()) setUrls((old) => ({ ...old, [evidenceId]: signed.url }));
    } catch (e) {
      if (valid()) setError(e instanceof Error ? e.message : 'Evidence access unavailable.');
    }
  }
  return (
    <Screen title="Private booking dispute">
      {error ? <Label accessibilityRole="alert">{error}</Label> : null}
      {!details ? (
        <Label>Loading case…</Label>
      ) : (
        <>
          <Label>Status: {details.dispute.status}</Label>
          <Label>{details.dispute.statement}</Label>
          {details.dispute.resolution ? (
            <Label>Resolution: {details.dispute.resolution}</Label>
          ) : null}
          {details.dispute.appeal_statement ? (
            <Label>Appeal: {details.dispute.appeal_statement}</Label>
          ) : null}
          <Label>
            Case retention review deadline:{' '}
            {new Date(details.dispute.retention_until).toLocaleDateString()}
          </Label>
          {details.decisions.map((decision) => (
            <Label key={decision.id}>
              {decision.status}: {decision.resolution}
            </Label>
          ))}
          <Label>
            Private evidence · Only the submitting participant and authorized reviewers can access
            it.
          </Label>
          {details.evidence.map((item) => (
            <React.Fragment key={item.id}>
              <Label>
                {item.kind} · {item.state} · expires{' '}
                {new Date(item.expires_at).toLocaleDateString()}
              </Label>
              {item.note ? <Label>{item.note}</Label> : null}
              {item.kind === 'image' && item.state === 'READY' ? (
                <PrimaryButton
                  secondary
                  title="View private image (up to 60 seconds)"
                  onPress={() => void view(item.id)}
                />
              ) : null}
              {urls[item.id] && item.state === 'READY' ? (
                <Image
                  source={{ uri: urls[item.id] }}
                  accessibilityLabel="Private dispute evidence"
                  style={{ width: '100%', height: 240, resizeMode: 'contain' }}
                />
              ) : null}
              {owner && !['DELETED', 'EXPIRED', 'RESERVED'].includes(item.state) ? (
                <PrimaryButton
                  secondary
                  title="Withdraw this evidence"
                  disabled={busy}
                  onPress={() =>
                    void act(() => services!.trust.evidenceRemove(item.id, session!.user.id))
                  }
                />
              ) : null}
            </React.Fragment>
          ))}
          {owner || reviewer ? (
            <Field
              label={
                owner ? 'Additional note or appeal explanation' : 'Decision reason for case owner'
              }
              value={text}
              onChangeText={setText}
              multiline
              maxLength={owner ? 3000 : 2000}
            />
          ) : null}
          {owner ? (
            <>
              <PrimaryButton
                title="Add private note"
                disabled={
                  busy || text.trim().length < 10 || details.dispute.status === 'FINAL_RESOLVED'
                }
                onPress={() =>
                  void act(() => services!.trust.disputeNote(id, text, session!.user.id))
                }
              />
              {details.dispute.status === 'RESOLVED' ? (
                <>
                  <Label>
                    You may appeal once within 30 days of resolution. Another independent reviewer
                    must decide it.
                  </Label>
                  <PrimaryButton
                    title="Appeal resolution"
                    disabled={busy || text.trim().length < 20}
                    onPress={() =>
                      void act(() => services!.trust.disputeAppeal(id, text, session!.user.id))
                    }
                  />
                </>
              ) : null}
              {configuration.enabled ? (
                <>
                  <Label>
                    Images only: JPEG/PNG, up to{' '}
                    {Math.floor((configuration.max_bytes || 0) / 1048576)} MiB and 12 megapixels.
                    Images are resized and metadata is removed. Never upload IDs, biometrics,
                    intimate images or unrelated personal data. Ordinary access expires after{' '}
                    {configuration.retention_days} days. Expired copies enter the approved cleanup
                    workflow; specially authorized retention operators may access them for deletion.
                    Previously issued links can remain usable for up to 60 seconds, bounded by the
                    access deadline.
                  </Label>
                  <Pressable
                    accessibilityRole="checkbox"
                    accessibilityState={{ checked: consent }}
                    onPress={() =>
                      setConsentedVersion(consent ? null : configuration.version || null)
                    }
                    style={{ minHeight: 48, justifyContent: 'center' }}
                  >
                    <Label>
                      {consent ? '✓ ' : ''}I have permission to share this evidence for case review
                      under {configuration.version}.
                    </Label>
                  </Pressable>
                  <PrimaryButton
                    title="Choose and upload private image"
                    disabled={busy || !consent || details.dispute.status === 'FINAL_RESOLVED'}
                    onPress={() => void upload()}
                  />
                </>
              ) : (
                <Label>
                  Image evidence is disabled until its privacy policy and private storage are
                  approved. You can still submit a text note.
                </Label>
              )}
            </>
          ) : null}
          {reviewer && !owner
            ? (details.dispute.status === 'APPEALED' || details.dispute.status === 'ESCALATED'
                ? ['ESCALATED', 'FINAL_RESOLVED']
                : ['UNDER_REVIEW', 'RESOLVED', 'ESCALATED']
              ).map((status) => (
                <PrimaryButton
                  key={status}
                  secondary
                  title={status.replaceAll('_', ' ')}
                  disabled={busy || text.trim().length < 3}
                  onPress={() =>
                    void act(() =>
                      services!.trust.disputeDecision(id, status, text, session!.user.id),
                    )
                  }
                />
              ))
            : null}
        </>
      )}
      <PrimaryButton title="Refresh case" secondary disabled={busy} onPress={() => void load()} />
    </Screen>
  );
}
