import { useCatalog } from '../backend/CatalogProvider';
import React, { useState } from 'react';
import { Modal, Text, View } from 'react-native';
import { useLocalSearchParams } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';

import { imageSource } from '../talentMedia';
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
  const { talents, mock } = useCatalog();
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
    ? [
        {
          image: talent.portrait,
          label: `${talent.name} ${mock ? 'sample portrait' : 'portrait'}`,
          uri: talent.portraitUri,
        },
        ...talent.portfolio,
      ]
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
      <SampleNote
        text={
          mock
            ? 'Sample stock gallery · different models · not actual client work'
            : 'Published portfolio'
        }
      />
      {tab === 'Photos' && images.length ? (
        <PortfolioGrid images={images} onOpen={setSelected} />
      ) : (
        <EmptyState
          title={`No ${tab.toLowerCase()} yet`}
          detail={
            mock
              ? 'There are no sample assets in this category. Explore Photos to view the bundled portrait studies.'
              : 'No published assets are available in this format.'
          }
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
              {mock ? 'Sample photograph' : 'Photograph'}{' '}
              {selected === null ? '' : `${selected + 1} / ${images.length}`}
            </Text>
            <IconButton name="close" label="Close media viewer" onPress={() => setSelected(null)} />
          </View>
          <View style={{ flex: 1, justifyContent: 'center' }}>
            {selected !== null && images[selected] && (
              <TalentImage
                key={images[selected].image}
                source={imageSource(images[selected].image, images[selected].uri)}
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
            <SampleNote text={mock ? 'Licensed stock sample' : 'Talent portfolio'} />
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
