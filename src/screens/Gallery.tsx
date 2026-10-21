import React, { useState } from 'react';
import { Modal, Text, View } from 'react-native';
import { useLocalSearchParams } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import { talents } from '../talent';
import { talentMedia } from '../talentMedia';
import { TalentImage } from '../TalentImage';
import {
  CategoryChip,
  Chips,
  EmptyState,
  IconButton,
  PortfolioGrid,
  SampleNote,
  Screen,
} from '../components/ui';
import { c, f } from '../theme';
export default function Gallery() {
  const { id, image } = useLocalSearchParams<{ id: string; image?: string }>();
  const talent = talents.find((t) => t.id === id);
  const [tab, setTab] = useState('Photos');
  const [selected, setSelected] = useState<number | null>(() => {
    const index = Number(image);
    return image !== undefined &&
      Number.isInteger(index) &&
      index >= 0 &&
      index < (talent ? talent.portfolio.length + 1 : 0)
      ? index
      : null;
  });
  const images = talent
    ? [{ image: talent.portrait, label: `${talent.name} sample portrait` }, ...talent.portfolio]
    : [];
  return (
    <Screen title={talent?.name || 'Portfolio gallery'}>
      <Chips>
        {['Photos', 'Videos', 'Reels', 'Brand work'].map((value) => (
          <CategoryChip
            key={value}
            title={value}
            active={tab === value}
            onPress={() => setTab(value)}
          />
        ))}
      </Chips>
      <SampleNote text="Sample stock gallery · different models · not actual client work" />
      {tab === 'Photos' && images.length ? (
        <PortfolioGrid images={images} onOpen={setSelected} />
      ) : (
        <EmptyState
          title={`No ${tab.toLowerCase()} yet`}
          detail="There are no sample assets in this category. Explore Photos to view the bundled portrait studies."
        />
      )}
      <Modal
        visible={selected !== null}
        animationType="fade"
        onRequestClose={() => setSelected(null)}
      >
        <SafeAreaView style={{ flex: 1, backgroundColor: c.background }}>
          <View
            style={{
              flexDirection: 'row',
              justifyContent: 'space-between',
              alignItems: 'center',
              padding: 12,
            }}
          >
            <Text style={{ fontFamily: f.semibold, color: c.text }}>
              Sample photograph {selected === null ? '' : `${selected + 1} / ${images.length}`}
            </Text>
            <IconButton name="close" label="Close media viewer" onPress={() => setSelected(null)} />
          </View>
          <View style={{ flex: 1, justifyContent: 'center' }}>
            {selected !== null && images[selected] && (
              <TalentImage
                key={images[selected].image}
                source={talentMedia[images[selected].image]}
                label={images[selected].label}
                fit="contain"
                style={{ height: '100%', borderRadius: 0 }}
              />
            )}
          </View>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between', padding: 16 }}>
            <IconButton
              name="chevron-back"
              label="Previous photograph"
              onPress={() =>
                setSelected((current) =>
                  current === null ? 0 : (current - 1 + images.length) % images.length,
                )
              }
            />
            <SampleNote text="Licensed stock sample" />
            <IconButton
              name="chevron-forward"
              label="Next photograph"
              onPress={() =>
                setSelected((current) => (current === null ? 0 : (current + 1) % images.length))
              }
            />
          </View>
        </SafeAreaView>
      </Modal>
    </Screen>
  );
}
