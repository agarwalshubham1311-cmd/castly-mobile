import React, { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { talents, money } from '../talent';
import { TalentImage } from '../TalentImage';
import { talentMedia } from '../talentMedia';
import DateField from '../components/DateField';
import { BookingForm, validateBooking } from '../demo';
import { useDemo } from '../store';
import { c, f } from '../theme';
import {
  ChoiceModal,
  EmptyState,
  Field,
  Heading,
  Label,
  PrimaryButton,
  SampleNote,
  Screen,
} from '../components/ui';
export default function BookingRequest() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const talent = talents.find((t) => t.id === id);
  const { addBooking } = useDemo();
  const [projectOpen, setProjectOpen] = useState(false);
  const [confirmation, setConfirmation] = useState('');
  const [form, setForm] = useState<BookingForm>({
    project: '',
    date: '',
    location: '',
    requirements: '',
    budget: '',
  });
  const [errors, setErrors] = useState<Partial<Record<keyof BookingForm, string>>>({});
  if (!talent)
    return (
      <Screen title="Request booking">
        <EmptyState title="Talent not found" detail="Please select a sample talent first." />
      </Screen>
    );
  const submit = () => {
    const validation = validateBooking(form);
    setErrors(validation);
    if (Object.keys(validation).length === 0) {
      setConfirmation(addBooking(talent.id, form));
    }
  };
  if (confirmation)
    return (
      <Screen title="Local request saved">
        <EmptyState
          title="Your local draft is ready"
          detail="Saved for this demo session only. Nothing was sent to the talent or a backend, and no payment was taken."
          action="View local request"
          onPress={() => router.replace(`/booking-detail/${confirmation}`)}
        />
        <PrimaryButton title="Back to discovery" secondary onPress={() => router.replace('/')} />
      </Screen>
    );
  const update = (key: keyof BookingForm) => (value: string) =>
    setForm((current) => ({ ...current, [key]: value }));
  return (
    <Screen
      title="Request booking"
      footer={<PrimaryButton title="Save local request" onPress={submit} />}
    >
      <View
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          gap: 12,
          backgroundColor: c.white,
          padding: 12,
          borderRadius: 16,
        }}
      >
        <TalentImage
          source={talentMedia[talent.portrait]}
          label={`Sample portrait for ${talent.name}`}
          style={{ height: 66, width: 56, borderRadius: 12 }}
        />
        <View style={{ flex: 1 }}>
          <Heading>{talent.name}</Heading>
          <Label>
            {talent.category.slice(0, -1)} · {talent.city}
          </Label>
        </View>
      </View>
      <SampleNote text="Local demo only · no request will be sent" />
      <View style={{ gap: 7 }}>
        <Text style={{ fontFamily: f.semibold, color: c.text, fontSize: 12 }}>Project type</Text>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Choose project type"
          onPress={() => setProjectOpen(true)}
          style={{
            minHeight: 48,
            borderWidth: 1,
            borderColor: errors.project ? c.error : c.border,
            backgroundColor: '#F7F4FC',
            borderRadius: 12,
            padding: 12,
          }}
        >
          <Label style={{ color: form.project ? c.text : c.secondary }}>
            {form.project || 'Select project type'} ⌄
          </Label>
        </Pressable>
        {errors.project && (
          <Text accessibilityRole="alert" style={{ color: c.error, fontSize: 12 }}>
            {errors.project}
          </Text>
        )}
      </View>
      <DateField value={form.date} onChange={update('date')} error={errors.date} />
      <Field
        label="Location"
        placeholder="Enter city or studio"
        value={form.location}
        onChangeText={update('location')}
        error={errors.location}
      />
      <Field
        label="Project requirements"
        placeholder="Tell us about your project, deliverables and creative direction…"
        multiline
        value={form.requirements}
        onChangeText={update('requirements')}
        error={errors.requirements}
      />
      <Field
        label="Indicative budget (INR)"
        placeholder="Enter your proposed budget"
        keyboardType="decimal-pad"
        value={form.budget}
        onChangeText={update('budget')}
        error={errors.budget}
      />
      <Label>
        Sample starting day rate: {money(talent.rate)}. Final scope and pricing are not confirmed by
        this form. No commissions or booking payments are collected.
      </Label>
      <ChoiceModal
        visible={projectOpen}
        title="Project type"
        options={[
          'Brand photoshoot',
          'Fashion editorial',
          'Content creation',
          'Live performance',
          'Other creative project',
        ]}
        onSelect={update('project')}
        onClose={() => setProjectOpen(false)}
      />
    </Screen>
  );
}
