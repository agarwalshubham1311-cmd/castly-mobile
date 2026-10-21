import React, { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { router } from 'expo-router';
import { categories, talents } from '../talent';
import { c, f } from '../theme';
import { talentMedia } from '../talentMedia';
import { TalentImage } from '../TalentImage';
import { useDemo } from '../store';
import {
  ChoiceModal,
  FeaturedTalentCard,
  Icon,
  IconButton,
  IconName,
  SampleNote,
  Screen,
  SectionTitle,
  TalentGrid,
} from '../components/ui';
const categoryIcons: IconName[] = [
  'grid-outline',
  'film-outline',
  'person-outline',
  'camera-outline',
  'musical-notes-outline',
  'aperture-outline',
];
export default function Discovery() {
  const [locationOpen, setLocationOpen] = useState(false);
  const { filters, setFilters, setCategory, setQuery } = useDemo();
  const header = (
    <View style={{ gap: 18, marginBottom: 18 }}>
      <View style={s.brandRow}>
        <Text accessibilityRole="header" style={s.logo}>
          castly<Text style={{ color: c.primary }}>✦</Text>
        </Text>
        <View style={{ flexDirection: 'row', alignItems: 'center' }}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Choose discovery location"
            onPress={() => setLocationOpen(true)}
            style={s.location}
          >
            <Icon name="location-outline" size={14} />
            <Text style={s.locationText}>
              {filters.city === 'All cities' ? 'Explore' : filters.city}
            </Text>
            <Icon name="chevron-down" size={12} />
          </Pressable>
          <IconButton
            name="notifications-outline"
            label="Notifications"
            onPress={() => router.push('/notifications')}
          />
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Open account profile"
            onPress={() => router.push('/account')}
            style={s.avatar}
          >
            <TalentImage
              source={talentMedia['portrait-03']}
              label="Sample account portrait"
              style={{ height: 32, width: 32, borderRadius: 16 }}
            />
          </Pressable>
        </View>
      </View>
      <LinearGradient
        colors={['#F0E7FF', '#F9F5FF', '#E6D9FA']}
        start={{ x: 0, y: 0 }}
        end={{ x: 1, y: 1 }}
        style={s.hero}
      >
        <TalentImage
          source={talentMedia[talents[0].portrait]}
          label="Editorial sample talent portrait"
          style={s.heroImage}
        />
        <View style={s.heroCopy}>
          <Text style={s.eyebrow}>WHERE TALENT SHINES</Text>
          <Text style={s.heroTitle}>
            Find the perfect{'\n'}talent for your{'\n'}
            <Text style={{ color: c.primaryDark }}>next big idea.</Text>
          </Text>
          <Text style={s.heroCaption}>Your vision. Their brilliance.</Text>
        </View>
      </LinearGradient>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Search talent"
        onPress={() => router.push('/search')}
        style={s.searchEntry}
      >
        <Icon name="search-outline" size={19} />
        <Text style={s.searchPlaceholder}>Search creators, models, actors…</Text>
        <Icon name="options-outline" size={19} color={c.primaryDark} />
      </Pressable>
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={{ gap: 12 }}
      >
        {categories.map((category, index) => (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={`Browse ${category}`}
            key={category}
            onPress={() => {
              setCategory(category);
              setQuery('');
              router.push('/search');
            }}
            style={s.category}
          >
            <View
              style={[
                s.categoryIcon,
                {
                  backgroundColor:
                    index === 0 ? c.primary : [c.peach, c.gold, c.lavender][index % 3],
                },
              ]}
            >
              <Icon name={categoryIcons[index]} color={index === 0 ? c.white : c.text} />
            </View>
            <Text style={s.categoryLabel}>{category === 'All talent' ? 'All' : category}</Text>
          </Pressable>
        ))}
      </ScrollView>
      <View>
        <SectionTitle
          title="Featured creators"
          action="See all"
          onPress={() => {
            setCategory('All talent');
            router.push('/search');
          }}
        />
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          snapToInterval={146}
          decelerationRate="fast"
          contentContainerStyle={{ gap: 10, paddingBottom: 8 }}
        >
          {talents.slice(0, 3).map((t) => (
            <FeaturedTalentCard key={t.id} talent={t} />
          ))}
        </ScrollView>
      </View>
      <View style={{ gap: 4 }}>
        <SectionTitle
          title="Trending talent"
          action="Discover more"
          onPress={() => router.push('/search')}
        />
        <SampleNote text="Fictional talent · stock photos · indicative rates" />
      </View>
    </View>
  );
  return (
    <Screen scroll={false}>
      <TalentGrid data={talents} header={header} />
      <ChoiceModal
        visible={locationOpen}
        title="Discover by location"
        options={['All cities', 'Mumbai', 'Delhi', 'Bengaluru']}
        onClose={() => setLocationOpen(false)}
        onSelect={(city) => {
          setFilters({ ...filters, city });
          router.push('/search');
        }}
      />
    </Screen>
  );
}
const s = StyleSheet.create({
  brandRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 4 },
  logo: { fontFamily: f.extrabold, fontSize: 31, letterSpacing: -2, color: '#171038' },
  location: {
    minHeight: 44,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    paddingHorizontal: 8,
    borderRadius: 20,
    backgroundColor: '#F1EBFF',
  },
  locationText: { fontFamily: f.semibold, color: c.text, fontSize: 10 },
  avatar: { height: 44, width: 44, alignItems: 'center', justifyContent: 'center' },
  hero: { height: 185, borderRadius: 22, overflow: 'hidden' },
  heroImage: { position: 'absolute', height: 185, width: '48%', right: 0, borderRadius: 0 },
  heroCopy: { padding: 16, width: '67%', gap: 7 },
  eyebrow: { fontSize: 9, letterSpacing: 1.4, fontFamily: f.extrabold, color: c.primaryDark },
  heroTitle: {
    fontFamily: f.extrabold,
    fontSize: 23,
    color: c.text,
    letterSpacing: -0.9,
    lineHeight: 28,
  },
  heroCaption: { fontFamily: f.medium, fontSize: 10, color: c.secondary },
  searchEntry: {
    minHeight: 50,
    backgroundColor: c.white,
    borderRadius: 26,
    paddingHorizontal: 15,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    marginTop: -34,
    borderWidth: 1,
    borderColor: c.border,
    shadowColor: c.primary,
    shadowOpacity: 0.12,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 4 },
    elevation: 3,
  },
  searchPlaceholder: { flex: 1, fontFamily: f.regular, fontSize: 11, color: c.secondary },
  category: { minWidth: 52, alignItems: 'center', gap: 7 },
  categoryIcon: {
    width: 48,
    height: 48,
    borderRadius: 18,
    alignItems: 'center',
    justifyContent: 'center',
  },
  categoryLabel: { color: c.text, fontFamily: f.medium, fontSize: 10 },
});
