import React, { useState } from 'react';
import { FlatList, Pressable, Text, View } from 'react-native';
import { router } from 'expo-router';
import { useDemo } from '../store';
import { categories, talents } from '../talent';
import { TalentImage } from '../TalentImage';
import { talentMedia } from '../talentMedia';
import { c, f } from '../theme';
import {
  CategoryChip,
  Chips,
  EmptyState,
  IconButton,
  LoadingSkeleton,
  PriceBadge,
  SampleNote,
  Screen,
  useLayout,
} from '../components/ui';
export default function Saved() {
  const { saved, toggleSaved, hydrated, storageError } = useDemo();
  const { padding } = useLayout();
  const [category, setCategory] = useState('All talent');
  const results = talents.filter(
    (t) => saved.includes(t.id) && (category === 'All talent' || t.category === category),
  );
  return (
    <Screen title="Saved talent" scroll={false}>
      <FlatList
        data={results}
        keyExtractor={(t) => t.id}
        contentContainerStyle={{ paddingHorizontal: padding, gap: 12, paddingBottom: 24 }}
        ListHeaderComponent={
          <View style={{ gap: 10 }}>
            <Chips>
              {categories.map((cat) => (
                <CategoryChip
                  key={cat}
                  title={cat === 'All talent' ? 'All' : cat}
                  active={category === cat}
                  onPress={() => setCategory(cat)}
                />
              ))}
            </Chips>
            <SampleNote text={storageError || 'Your local shortlist · fictional sample talent'} />
          </View>
        }
        renderItem={({ item }) => (
          <View
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              backgroundColor: c.white,
              borderRadius: 16,
              padding: 10,
              borderWidth: 1,
              borderColor: c.border,
            }}
          >
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={`View ${item.name}'s profile`}
              onPress={() => router.push(`/talent/${item.id}`)}
              style={{ flexDirection: 'row', gap: 12, alignItems: 'center', flex: 1 }}
            >
              <TalentImage
                source={talentMedia[item.portrait]}
                label={`Sample portrait for ${item.name}`}
                style={{ height: 76, width: 64, borderRadius: 12 }}
              />
              <View style={{ flex: 1, gap: 5 }}>
                <Text style={{ fontFamily: f.bold, color: c.text, fontSize: 14 }}>{item.name}</Text>
                <Text style={{ fontFamily: f.regular, color: c.secondary, fontSize: 11 }}>
                  {item.category.slice(0, -1)} · {item.city}
                </Text>
                <PriceBadge rate={item.rate} />
              </View>
            </Pressable>
            <IconButton
              name="heart"
              label={`Unsave ${item.name}`}
              selected
              onPress={() => toggleSaved(item.id)}
            />
          </View>
        )}
        ListEmptyComponent={
          !hydrated ? (
            <LoadingSkeleton label="Restoring saved talent" />
          ) : (
            <EmptyState
              title={
                saved.length ? 'No saved talent in this category' : 'Your shortlist starts here'
              }
              detail="Tap the heart on a talent card to keep a creative connection close."
              action="Explore talent"
              onPress={() => router.push('/search')}
            />
          )
        }
      />
    </Screen>
  );
}
