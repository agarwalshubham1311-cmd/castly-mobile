import React, { useState } from 'react';
import { Switch, Text, View } from 'react-native';
import Slider from '@react-native-community/slider';
import { router } from 'expo-router';
import { categories, defaultFilters, findTalents, money } from '../talent';
import { useDemo } from '../store';
import { c, f } from '../theme';
import {
  CategoryChip,
  Heading,
  IconButton,
  Label,
  PrimaryButton,
  SampleNote,
  Screen,
  SearchBar,
} from '../components/ui';
const cities = ['All cities', 'Mumbai', 'Delhi', 'Bengaluru'];
const skills = [
  'Acting',
  'Editorial',
  'Lifestyle',
  'Video',
  'Photography',
  'Vocals',
  'Guitar',
  'Commercial',
  'Runway',
  'Brand films',
];
export default function Filters() {
  const store = useDemo();
  const [draft, setDraft] = useState({ ...store.filters });
  const [category, setCategory] = useState(store.category);
  const [cityQuery, setCityQuery] = useState('');
  const count = findTalents(store.query, category, draft).length;
  const clear = () => {
    setDraft({ ...defaultFilters, minRate: 0, skills: [] });
    setCategory('All talent');
    setCityQuery('');
  };
  const apply = () => {
    store.setFilters(draft);
    store.setCategory(category);
    if (router.canGoBack()) router.back();
    else router.replace('/search');
  };
  return (
    <Screen
      title="Filters"
      action={<IconButton name="refresh-outline" label="Clear all filters" onPress={clear} />}
      footer={<PrimaryButton title={`Show ${count} results`} onPress={apply} />}
    >
      <Heading>Location</Heading>
      <SearchBar
        label="Search city"
        value={cityQuery}
        onChange={setCityQuery}
        placeholder="Search city"
      />
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 7 }}>
        {cities
          .filter((city) => city.toLowerCase().includes(cityQuery.toLowerCase()))
          .map((city) => (
            <CategoryChip
              key={city}
              title={city}
              active={draft.city === city}
              onPress={() => setDraft({ ...draft, city })}
            />
          ))}
        {!cities.some((city) => city.toLowerCase().includes(cityQuery.toLowerCase())) && (
          <Label>No supported cities match this search.</Label>
        )}
      </View>
      <Heading>Category</Heading>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 7 }}>
        {categories.map((cat) => (
          <CategoryChip
            key={cat}
            title={cat}
            active={cat === category}
            onPress={() => setCategory(cat)}
          />
        ))}
      </View>
      <View style={{ gap: 10 }}>
        <Heading>Budget per day</Heading>
        <Text style={{ fontFamily: f.bold, fontSize: 14, color: c.primaryDark }}>
          {money(draft.minRate ?? 0)} – {money(draft.maxRate)}
        </Text>
        <Label>Minimum daily rate</Label>
        <Slider
          accessibilityLabel="Minimum daily rate"
          minimumValue={0}
          maximumValue={30000}
          step={1000}
          value={draft.minRate ?? 0}
          onValueChange={(value) =>
            setDraft((current) => ({
              ...current,
              minRate: value,
              maxRate: Math.max(current.maxRate, value),
            }))
          }
          minimumTrackTintColor={c.primary}
          thumbTintColor={c.primaryDark}
          style={{ height: 44 }}
        />
        <Label>Maximum daily rate</Label>
        <Slider
          accessibilityLabel="Maximum daily rate"
          minimumValue={0}
          maximumValue={30000}
          step={1000}
          value={draft.maxRate}
          onValueChange={(value) =>
            setDraft((current) => ({
              ...current,
              maxRate: value,
              minRate: Math.min(current.minRate ?? 0, value),
            }))
          }
          minimumTrackTintColor={c.primary}
          thumbTintColor={c.primaryDark}
          style={{ height: 44 }}
        />
      </View>
      <Heading>Skills / niche</Heading>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 7 }}>
        {skills.map((skill) => (
          <CategoryChip
            key={skill}
            title={skill}
            active={!!draft.skills?.includes(skill)}
            onPress={() =>
              setDraft({
                ...draft,
                skills: draft.skills?.includes(skill)
                  ? draft.skills.filter((s) => s !== skill)
                  : [...(draft.skills ?? []), skill],
              })
            }
          />
        ))}
      </View>
      <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
        <Label style={{ color: c.text }}>Available talent only</Label>
        <Switch
          accessibilityLabel="Available talent only"
          value={draft.availableOnly}
          onValueChange={(availableOnly) => setDraft({ ...draft, availableOnly })}
          trackColor={{ false: c.border, true: c.primary }}
        />
      </View>
      <PrimaryButton title="Clear filters" secondary onPress={clear} />
      <SampleNote text="Changes apply only when you select Show results." />
    </Screen>
  );
}
