import { BackendNotice } from '../components/BackendNotice';
import { useCatalog } from '../backend/CatalogProvider';
import React, { useState } from 'react';
import { Pressable, ScrollView, Share, Text, View } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import * as Linking from 'expo-linking';

import { imageSource } from '../talentMedia';
import { TalentImage } from '../TalentImage';
import { useDemo } from '../store';
import { c, f } from '../theme';
import {
  CategoryChip,
  Chips,
  EmptyState,
  Heading,
  IconButton,
  Label,
  PortfolioGrid,
  PriceBadge,
  PrimaryButton,
  SampleNote,
  Screen,
  SectionTitle,
  useLayout,
} from '../components/ui';
export default function TalentProfile() {
  const catalog = useCatalog();
  const { talents, mock } = catalog;
  const { width, padding, fontScale } = useLayout();
  const { id } = useLocalSearchParams<{ id: string }>();
  const talent = talents.find((t) => t.id === id);
  const [tab, setTab] = useState('Portfolio');
  const [expanded, setExpanded] = useState(false);
  const [shareLink, setShareLink] = useState('');
  const { saved, toggleSaved, hydrated, openConversation } = useDemo();
  if (!talent && !mock && (catalog.loading || catalog.error))
    return (
      <Screen title="Talent profile">
        <BackendNotice />
      </Screen>
    );
  if (!talent)
    return (
      <Screen title="Talent profile">
        <EmptyState
          title="Talent not found"
          detail={
            mock
              ? 'This sample profile is unavailable.'
              : 'This profile is unpublished or unavailable.'
          }
          action="Explore talent"
          onPress={() => router.replace('/search')}
        />
      </Screen>
    );
  const images = [
    { image: talent.portrait, label: `${talent.name} portrait`, uri: talent.portraitUri },
    ...talent.portfolio,
  ];
  const message = () => {
    openConversation(talent.id);
    router.push(`/chat/${talent.id}`);
  };
  const share = async () => {
    const url = Linking.createURL(`/talent/${talent.id}`);
    setShareLink(url);
    try {
      await Share.share({ message: `Castly sample talent: ${talent.name}. ${url}` });
    } catch {
      /* The selectable link remains available on platforms without a share sheet. */
    }
  };
  return (
    <Screen
      title={talent.name}
      scroll={false}
      action={<IconButton name="share-outline" label="Share talent" onPress={share} />}
      footer={
        <View style={{ flexDirection: fontScale > 1.3 ? 'column' : 'row', gap: 10 }}>
          <View style={fontScale > 1.3 ? undefined : { flex: 1 }}>
            <PrimaryButton title="Message" secondary onPress={message} />
          </View>
          <View style={fontScale > 1.3 ? undefined : { flex: 1.6 }}>
            <PrimaryButton
              title="Request booking"
              icon="calendar-outline"
              onPress={() => router.push(`/booking/${talent.id}`)}
            />
          </View>
        </View>
      }
    >
      <ScrollView
        style={{ flex: 1, minHeight: 0 }}
        stickyHeaderIndices={[1]}
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={{ paddingBottom: 24 }}
      >
        <View style={{ paddingHorizontal: padding, paddingTop: 8, gap: 10 }}>
          <View style={{ position: 'relative' }}>
            <TalentImage
              source={imageSource(talent.portrait, talent.portraitUri)}
              label={`${mock ? 'Sample portrait for' : 'Portrait of'} ${talent.name}`}
              style={{
                height: fontScale > 1.3 ? 190 : Math.round(Math.min(260, width * 0.62)),
                borderRadius: 22,
              }}
            />
            <View
              style={{
                position: 'absolute',
                right: 10,
                top: 10,
                backgroundColor: c.white,
                borderRadius: 24,
              }}
            >
              <IconButton
                name={saved.includes(talent.id) ? 'heart' : 'heart-outline'}
                label={`${saved.includes(talent.id) ? 'Unsave' : 'Save'} ${talent.name}`}
                selected={saved.includes(talent.id)}
                onPress={() => {
                  if (hydrated) toggleSaved(talent.id);
                }}
              />
            </View>
          </View>
          <View
            style={{
              flexDirection: 'row',
              alignItems: 'flex-start',
              justifyContent: 'space-between',
              gap: 8,
            }}
          >
            <View style={{ flex: 1, gap: 5 }}>
              <Heading style={{ fontSize: 23, lineHeight: 30 }}>{talent.name}</Heading>
              <Label>
                {talent.category.slice(0, -1)} · {talent.city}
              </Label>
            </View>
            <PriceBadge rate={talent.rate} large />
          </View>
          <SampleNote
            text={
              mock
                ? 'Fictional profile · stock photography · indicative pricing'
                : 'Talent profile · indicative pricing'
            }
          />
          {!!shareLink && (
            <View style={{ backgroundColor: c.lavender, padding: 12, borderRadius: 12 }}>
              <Label>Share this sample profile</Label>
              <Text selectable style={{ color: c.primaryDark, fontFamily: f.medium, fontSize: 12 }}>
                {shareLink}
              </Text>
            </View>
          )}
          <Chips>
            {talent.skills.map((skill) => (
              <View
                key={skill}
                style={{
                  backgroundColor: c.lavender,
                  paddingHorizontal: 12,
                  paddingVertical: 8,
                  borderRadius: 18,
                }}
              >
                <Text style={{ fontFamily: f.medium, fontSize: 11, color: c.text }}>{skill}</Text>
              </View>
            ))}
          </Chips>
          <View style={{ gap: 4 }}>
            <Text
              numberOfLines={expanded ? undefined : 2}
              style={{ color: c.text, fontFamily: f.regular, fontSize: 13, lineHeight: 21 }}
            >
              {expanded
                ? `${talent.bio}${mock ? ' This is a fictional development profile, not a representation of the stock model pictured.' : ''}`
                : talent.bio}
            </Text>
            <Pressable
              accessibilityRole="button"
              onPress={() => setExpanded(!expanded)}
              style={{ minHeight: 48, justifyContent: 'center' }}
            >
              <Text style={{ color: c.primaryDark, fontFamily: f.semibold, fontSize: 12 }}>
                {expanded ? 'Read less' : 'Read more'} ⌄
              </Text>
            </Pressable>
          </View>
        </View>
        <View
          style={{
            backgroundColor: c.background,
            paddingHorizontal: padding,
            paddingVertical: 8,
            borderBottomWidth: 1,
            borderColor: c.border,
          }}
        >
          <Chips>
            {['Portfolio', 'Services', 'Reviews', 'About'].map((value) => (
              <CategoryChip
                key={value}
                title={value}
                active={tab === value}
                onPress={() => setTab(value)}
              />
            ))}
          </Chips>
        </View>
        <View style={{ paddingHorizontal: padding, paddingTop: 12 }}>
          {tab === 'Portfolio' && (
            <View style={{ gap: 12 }}>
              <SectionTitle
                title="Portfolio"
                action="View gallery"
                onPress={() => router.push(`/gallery/${talent.id}`)}
              />
              <PortfolioGrid
                images={images}
                onOpen={(index) =>
                  router.push({
                    pathname: '/gallery/[id]',
                    params: { id: talent.id, image: String(index) },
                  })
                }
              />
              <SampleNote
                text={
                  mock
                    ? 'Stock studies featuring different models; not this talent’s actual work.'
                    : 'Portfolio shared by this talent'
                }
              />
            </View>
          )}
          {tab === 'Services' && (
            <View style={{ backgroundColor: c.white, borderRadius: 16, padding: 18, gap: 12 }}>
              <Heading>Creative services</Heading>
              {talent.skills.map((skill) => (
                <Label key={skill} style={{ color: c.text }}>
                  • {skill}
                </Label>
              ))}
              <Label>
                Scope and final rates are discussed directly. The displayed day rate is an
                indicative rate.
              </Label>
            </View>
          )}
          {tab === 'Reviews' && (
            <EmptyState
              title="No reviews to display"
              detail={
                mock
                  ? 'This fictional profile has no real reviews, ratings or verification claims.'
                  : 'Reviews are not enabled yet. No ratings or verification claims are shown.'
              }
            />
          )}
          {tab === 'About' && (
            <View style={{ gap: 12 }}>
              <Heading>About {talent.name.split(' ')[0]}</Heading>
              <Label>{talent.bio}</Label>
              <Label>
                Based in {talent.city}.{' '}
                {talent.available
                  ? mock
                    ? 'Available in the sample catalog.'
                    : 'Available for creative projects.'
                  : mock
                    ? 'Currently unavailable in the sample catalog.'
                    : 'Currently unavailable for projects.'}
              </Label>
              <SampleNote
                text={
                  mock
                    ? 'Names, availability, services and rates are fictional fixtures.'
                    : 'Availability and indicative rates are provided by the talent.'
                }
              />
            </View>
          )}
        </View>
      </ScrollView>
    </Screen>
  );
}
