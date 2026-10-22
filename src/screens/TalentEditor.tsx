import { portfolioImageType } from '../backend/media';
import { TalentImage } from '../TalentImage';
import React, { useEffect, useState } from 'react';
import { Platform, View } from 'react-native';
import * as ImagePicker from 'expo-image-picker';
import { useAuth } from '../auth/AuthProvider';
import { useCatalog } from '../backend/CatalogProvider';
import { services } from '../backend';
import { readableError } from '../backend/errors';
import { validateTalent } from '../backend/validation';
import type { PortfolioAsset, TalentDraft } from '../backend/types';
import AccessibleSwitch from '../components/AccessibleSwitch';
import {
  CategoryChip,
  Chips,
  Field,
  Heading,
  Label,
  PrimaryButton,
  Screen,
} from '../components/ui';
import { c } from '../theme';
const empty: TalentDraft = {
  display_name: '',
  category_id: 'actors',
  city: '',
  day_rate: 0,
  bio: '',
  skills: [],
  available: true,
  published: false,
  portrait_path: null,
};
export default function TalentEditor() {
  const auth = useAuth();
  const userId = auth.session?.user.id;
  const catalog = useCatalog();
  const [draft, setDraft] = useState<TalentDraft>(empty);
  const [rate, setRate] = useState('0');
  const [skills, setSkills] = useState('');
  const [exists, setExists] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [assets, setAssets] = useState<PortfolioAsset[]>([]);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let active = true;
    if (!services || !userId) return;
    Promise.all([services.talent.own(userId), services.portfolio.list(userId)])
      .then(([profile, rows]) => {
        if (!active) return;
        if (profile) {
          setDraft(profile);
          setRate(String(profile.day_rate));
          setSkills(profile.skills.join(', '));
          setExists(true);
        } else {
          setDraft({ ...empty, display_name: auth.account?.display_name || '' });
        }
        setAssets(rows || []);
      })
      .catch((e) => {
        if (active) setLoadError(readableError(e));
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [userId, auth.account?.display_name, revision]);
  const update = <K extends keyof TalentDraft>(key: K, value: TalentDraft[K]) =>
    setDraft((current) => ({ ...current, [key]: value }));
  const save = async () => {
    const value = {
      ...draft,
      display_name: draft.display_name.trim(),
      city: draft.city.trim(),
      day_rate: Number(rate),
      skills: skills
        .split(',')
        .map((s) => s.trim())
        .filter(Boolean),
    };
    const issue = validateTalent(value);
    setError(issue);
    setMessage('');
    if (issue) return;
    setBusy(true);
    try {
      const row = exists
        ? await services!.talent.update(auth.session!.user.id, value)
        : await services!.talent.create(auth.session!.user.id, value);
      if (!row) throw new Error('Profile save was not confirmed.');
      setDraft(row);
      setExists(true);
      setMessage(
        row.published
          ? 'Talent profile published.'
          : 'Draft profile saved. It is not visible in discovery.',
      );
      await catalog.refresh();
    } catch (e) {
      setError(readableError(e));
    } finally {
      setBusy(false);
    }
  };
  const photo = async (portrait: boolean) => {
    setError('');
    setMessage('');
    setBusy(true);
    try {
      if (Platform.OS !== 'web') {
        const permission = await ImagePicker.requestMediaLibraryPermissionsAsync();
        if (!permission.granted)
          throw new Error(
            'Photo access was not granted. You can retry or allow access in device settings.',
          );
      }
      const result = await ImagePicker.launchImageLibraryAsync({
        mediaTypes: ['images'],
        allowsEditing: true,
        aspect: portrait ? [3, 4] : [3, 4],
        quality: 0.85,
        base64: true,
      });
      if (result.canceled) return;
      const selected = result.assets[0];
      if (!selected.base64)
        throw new Error('The selected photo could not be read. Try another image.');
      const bytes = Uint8Array.from(atob(selected.base64), (ch) => ch.charCodeAt(0));
      const type = portfolioImageType(bytes);
      const path = `${auth.session!.user.id}/${Date.now()}-${Math.random().toString(36).slice(2)}.${type.extension}`;
      await services!.media.upload(path, bytes.buffer, type.contentType);
      if (portrait) {
        update('portrait_path', path);
        setMessage('Portrait uploaded. Save the profile to apply it.');
      } else {
        try {
          const row = await services!.portfolio.add(
            auth.session!.user.id,
            path,
            'Portfolio photograph',
            assets.length,
          );
          if (row) setAssets((current) => [...current, row]);
          setMessage('Portfolio photograph added.');
          await catalog.refresh();
        } catch (e) {
          await services!.media.remove(path).catch(() => {});
          throw e;
        }
      }
    } catch (e) {
      setError(readableError(e));
    } finally {
      setBusy(false);
    }
  };
  const remove = async (asset: PortfolioAsset) => {
    setBusy(true);
    setError('');
    try {
      await services!.portfolio.remove(asset.id);
      setAssets((current) => current.filter((row) => row.id !== asset.id));
      await services!.media.remove(asset.path);
      await catalog.refresh();
    } catch (e) {
      setError(readableError(e));
    } finally {
      setBusy(false);
    }
  };
  if (auth.mode === 'mock')
    return (
      <Screen title="Talent profile">
        <Label>
          Profile editing requires a configured Supabase project. The sample catalog is unchanged in
          mock mode.
        </Label>
      </Screen>
    );
  if (loadError)
    return (
      <Screen title="Talent profile unavailable">
        <Label accessibilityRole="alert">{loadError}</Label>
        <PrimaryButton
          title="Retry talent profile"
          onPress={() => {
            setLoadError('');
            setLoading(true);
            setRevision((value) => value + 1);
          }}
        />
      </Screen>
    );
  if (loading)
    return (
      <Screen title="Talent profile">
        <Label>Loading your talent profile…</Label>
      </Screen>
    );
  return (
    <Screen title={exists ? 'Edit talent profile' : 'Create talent profile'}>
      <Heading>Your creative identity</Heading>
      <Label>
        Drafts are private. Publish when you are ready to appear in discovery. Upload only media you
        have rights to share.
      </Label>
      <Field
        label="Talent name"
        value={draft.display_name}
        onChangeText={(v) => update('display_name', v)}
        maxLength={100}
      />
      <Field
        label="City"
        value={draft.city}
        onChangeText={(v) => update('city', v)}
        maxLength={100}
      />
      <Chips>
        {['actors', 'models', 'creators', 'musicians', 'photographers'].map((category) => (
          <CategoryChip
            key={category}
            title={category[0].toUpperCase() + category.slice(1)}
            active={draft.category_id === category}
            onPress={() => update('category_id', category)}
          />
        ))}
      </Chips>
      <Field
        label="Indicative daily rate (INR)"
        keyboardType="numeric"
        value={rate}
        onChangeText={setRate}
      />
      <Field
        label="Biography"
        multiline
        value={draft.bio}
        onChangeText={(v) => update('bio', v)}
        maxLength={2000}
      />
      <Field label="Skills, separated by commas" value={skills} onChangeText={setSkills} />
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
        <Label>Available for projects</Label>
        <AccessibleSwitch
          label="Available for projects"
          value={draft.available}
          onValueChange={(v) => update('available', v)}
        />
      </View>
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
        <Label>Published in discovery</Label>
        <AccessibleSwitch
          label="Published in discovery"
          value={draft.published}
          onValueChange={(v) => update('published', v)}
        />
      </View>
      {!!error && (
        <>
          <Label accessibilityRole="alert" style={{ color: c.error }}>
            {error}
          </Label>
          <PrimaryButton
            title="Reload saved profile"
            secondary
            disabled={busy}
            onPress={() => {
              setLoading(true);
              setError('');
              setRevision((v) => v + 1);
            }}
          />
        </>
      )}
      {!!message && <Label accessibilityLiveRegion="polite">{message}</Label>}
      <PrimaryButton
        title={busy ? 'Please wait…' : 'Save talent profile'}
        disabled={busy}
        onPress={() => {
          void save();
        }}
      />
      {exists && (
        <>
          <Heading>Portrait & portfolio</Heading>
          {draft.portrait_path ? (
            <MediaPreview path={draft.portrait_path} />
          ) : (
            <Label>No portrait selected yet.</Label>
          )}
          <PrimaryButton
            title="Choose portrait"
            secondary
            disabled={busy}
            onPress={() => {
              void photo(true);
            }}
          />
          <PrimaryButton
            title="Add portfolio photograph"
            secondary
            disabled={busy}
            onPress={() => {
              void photo(false);
            }}
          />
          {assets.map((asset) => (
            <View key={asset.id} style={{ gap: 8 }}>
              <Label>{asset.label}</Label>
              <MediaPreview path={asset.path} />
              <PrimaryButton
                title={`Remove photograph ${asset.position + 1}`}
                secondary
                disabled={busy}
                onPress={() => {
                  void remove(asset);
                }}
              />
            </View>
          ))}
          {!assets.length && <Label>No portfolio photographs yet.</Label>}
        </>
      )}
    </Screen>
  );
}

function MediaPreview({ path }: { path: string }) {
  const [uri, setUri] = useState('');
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    services!.media
      .signedUrl(path)
      .then((url) => {
        if (active) setUri(url);
      })
      .catch((e) => {
        if (active) setError(readableError(e));
      });
    return () => {
      active = false;
    };
  }, [path]);
  return error ? (
    <Label accessibilityRole="alert">{error}</Label>
  ) : uri ? (
    <TalentImage
      source={{ uri }}
      label="Your portfolio photograph"
      style={{ height: 220, borderRadius: 16 }}
    />
  ) : (
    <Label>Loading photograph…</Label>
  );
}
