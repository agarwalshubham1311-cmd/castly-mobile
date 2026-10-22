import React, { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Keyboard,
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  useWindowDimensions,
} from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import Ionicons from '@expo/vector-icons/Ionicons';
import { router } from 'expo-router';
import type { BottomTabBarProps } from 'expo-router/js-tabs';
import { c, f, theme } from '../theme';
import { TalentImage } from '../TalentImage';
import { talentMedia } from '../talentMedia';
import { Talent, money } from '../talent';
import { useDemo } from '../store';
import type { Booking, Conversation } from '../demo';
export type IconName = React.ComponentProps<typeof Ionicons>['name'];
export const Icon = ({
  name,
  size = 20,
  color = c.text,
}: {
  name: IconName;
  size?: number;
  color?: string;
}) => <Ionicons accessible={false} aria-hidden={true} name={name} size={size} color={color} />;
export function useLayout() {
  const { width, fontScale } = useWindowDimensions();
  const container = Math.min(width, 600);
  const padding = container <= 360 ? 16 : 20;
  return {
    width: container,
    fontScale,
    padding,
    columns: fontScale > 1.4 ? 1 : 2,
    cardWidth: (container - padding * 2 - 12) / 2,
  };
}
export function Label({
  children,
  style,
}: {
  children: React.ReactNode;
  style?: React.ComponentProps<typeof Text>['style'];
}) {
  return <Text style={[styles.body, style]}>{children}</Text>;
}
export function Heading({
  children,
  style,
}: {
  children: React.ReactNode;
  style?: React.ComponentProps<typeof Text>['style'];
}) {
  return <Text style={[styles.heading, style]}>{children}</Text>;
}
export function SampleNote({
  text = 'Demo preview · fictional talent & local activity',
}: {
  text?: string;
}) {
  return <Text style={styles.sample}>{text}</Text>;
}
export function IconButton({
  name,
  label,
  onPress,
  selected = false,
}: {
  name: IconName;
  label: string;
  onPress: () => void;
  selected?: boolean;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{ selected }}
      onPress={onPress}
      style={({ pressed }) => [styles.iconButton, pressed && { opacity: 0.6 }]}
    >
      <Icon name={name} color={selected ? c.primary : c.text} />
    </Pressable>
  );
}
export function PrimaryButton({
  title,
  onPress,
  secondary = false,
  disabled = false,
  icon,
}: {
  title: string;
  onPress: () => void;
  secondary?: boolean;
  disabled?: boolean;
  icon?: IconName;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={title}
      accessibilityState={{ disabled }}
      aria-disabled={disabled}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        styles.primary,
        secondary && styles.secondaryButton,
        (pressed || disabled) && { opacity: 0.6 },
      ]}
    >
      {icon && <Icon name={icon} size={18} color={secondary ? c.primaryDark : c.white} />}
      <Text style={[styles.buttonText, secondary && { color: c.primaryDark }]}>{title}</Text>
    </Pressable>
  );
}
export function CategoryChip({
  title,
  active,
  onPress,
}: {
  title: string;
  active: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityState={{ selected: active }}
      aria-pressed={active}
      onPress={onPress}
      style={[styles.chip, active && styles.chipActive]}
    >
      <Text style={[styles.chipText, active && { color: c.white }]}>{title}</Text>
    </Pressable>
  );
}
export function FilterChip({ title, onRemove }: { title: string; onRemove: () => void }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`Remove ${title} filter`}
      onPress={onRemove}
      style={styles.chip}
    >
      <Text style={styles.chipText}>{title}</Text>
      <Icon name="close" size={13} />
    </Pressable>
  );
}
export function Chips({ children }: { children: React.ReactNode }) {
  return (
    <ScrollView
      horizontal
      showsHorizontalScrollIndicator={false}
      contentContainerStyle={{ gap: 6, paddingVertical: 2 }}
    >
      {children}
    </ScrollView>
  );
}
export function SearchBar({
  value,
  onChange,
  onFilters,
  onFocus,
  editable = true,
  label = 'Search talent by name, skill or city',
  placeholder = 'Search creators, models, actors…',
}: {
  value: string;
  onChange: (text: string) => void;
  onFilters?: () => void;
  onFocus?: () => void;
  editable?: boolean;
  label?: string;
  placeholder?: string;
}) {
  return (
    <View style={styles.searchRow}>
      <View style={styles.search}>
        <Icon name="search-outline" size={18} color={c.secondary} />
        <TextInput
          accessibilityLabel={label}
          value={value}
          onChangeText={onChange}
          onFocus={onFocus}
          editable={editable}
          placeholder={placeholder}
          placeholderTextColor={c.secondary}
          style={styles.input}
          returnKeyType="search"
        />
        {!!value && (
          <IconButton
            name="close-circle-outline"
            label="Clear search"
            onPress={() => onChange('')}
          />
        )}
      </View>
      {onFilters && <IconButton name="options-outline" label="Filters" onPress={onFilters} />}
    </View>
  );
}
export function TopBar({
  title,
  action,
  back = true,
}: {
  title: string;
  action?: React.ReactNode;
  back?: boolean;
}) {
  return (
    <View style={styles.topbar}>
      {back ? (
        <IconButton
          name="arrow-back"
          label="Back"
          onPress={() => (router.canGoBack() ? router.back() : router.replace('/'))}
        />
      ) : (
        <View style={{ width: 48 }} />
      )}
      <Text style={styles.topTitle}>{title}</Text>
      <View style={{ minWidth: 48, alignItems: 'flex-end' }}>{action}</View>
    </View>
  );
}
export function Screen({
  title,
  children,
  footer,
  action,
  scroll = true,
  back = true,
}: {
  title?: string;
  children: React.ReactNode;
  footer?: React.ReactNode;
  action?: React.ReactNode;
  scroll?: boolean;
  back?: boolean;
}) {
  const { padding } = useLayout();
  const insets = useSafeAreaInsets();
  return (
    <SafeAreaView edges={['top', 'left', 'right']} style={styles.screen}>
      <KeyboardAvoidingView
        style={{ flex: 1, minHeight: 0 }}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        {title && <TopBar title={title} action={action} back={back} />}
        {scroll ? (
          <ScrollView
            style={{ flex: 1, minHeight: 0 }}
            keyboardShouldPersistTaps="handled"
            keyboardDismissMode="on-drag"
            contentContainerStyle={{
              paddingHorizontal: padding,
              paddingTop: 8,
              paddingBottom: footer ? 20 : Math.max(insets.bottom, 20),
              gap: 18,
            }}
          >
            {children}
          </ScrollView>
        ) : (
          children
        )}
        {footer && (
          <View style={[styles.footer, { paddingBottom: Math.max(insets.bottom, 12) }]}>
            {footer}
          </View>
        )}
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}
export function EmptyState({
  title,
  detail,
  action,
  onPress,
}: {
  title: string;
  detail: string;
  action?: string;
  onPress?: () => void;
}) {
  return (
    <View style={styles.empty}>
      <View style={styles.emptyIcon}>
        <Icon name="sparkles-outline" color={c.primary} size={28} />
      </View>
      <Heading>{title}</Heading>
      <Label style={{ textAlign: 'center' }}>{detail}</Label>
      {action && onPress && <PrimaryButton title={action} onPress={onPress} secondary />}
    </View>
  );
}
export function LoadingSkeleton({ label = 'Loading talent' }: { label?: string }) {
  return (
    <View accessibilityLabel={label} style={styles.empty}>
      <ActivityIndicator color={c.primary} />
      <View style={{ width: '90%', height: 140, backgroundColor: c.lavender, borderRadius: 16 }} />
      <Label>{label}…</Label>
    </View>
  );
}
export function PriceBadge({ rate, large = false }: { rate: number; large?: boolean }) {
  if (large)
    return (
      <View style={[styles.priceBadge, { padding: 12, alignItems: 'center', gap: 3 }]}>
        <Text style={[styles.metadata, { color: c.primaryDark }]}>From</Text>
        <Text style={[styles.price, { fontSize: 16, lineHeight: 23 }]}>{money(rate)}</Text>
        <Text style={[styles.day, { color: c.primaryDark }]}>per day</Text>
      </View>
    );
  return (
    <View style={styles.priceBadge}>
      <Text style={styles.price}>
        From {money(rate)}
        <Text style={styles.day}> /day</Text>
      </Text>
    </View>
  );
}
function SaveButton({ talent }: { talent: Talent }) {
  const { saved, toggleSaved, hydrated } = useDemo();
  const selected = saved.includes(talent.id);
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${selected ? 'Unsave' : 'Save'} ${talent.name}`}
      accessibilityState={{ selected, disabled: !hydrated }}
      aria-pressed={selected}
      aria-disabled={!hydrated}
      disabled={!hydrated}
      onPress={() => toggleSaved(talent.id)}
      style={styles.saveTarget}
    >
      <View style={styles.saveCircle}>
        <Icon
          name={selected ? 'heart' : 'heart-outline'}
          color={selected ? c.primaryDark : c.text}
          size={17}
        />
      </View>
    </Pressable>
  );
}
export function TalentCard({ talent, featured = false }: { talent: Talent; featured?: boolean }) {
  return (
    <View style={[styles.card, featured && { width: 136 }]}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`View ${talent.name}'s profile`}
        onPress={() => router.push(`/talent/${talent.id}`)}
      >
        <TalentImage
          source={talentMedia[talent.portrait]}
          label={`Sample portrait for ${talent.name}`}
          style={{ height: undefined, aspectRatio: featured ? 1 : 3 / 4, borderRadius: 12 }}
        />
        <View style={styles.cardBody}>
          <Text numberOfLines={2} style={[styles.cardName, featured && { fontSize: 12 }]}>
            {talent.name}
          </Text>
          <Text numberOfLines={1} style={styles.metadata}>
            {talent.category.slice(0, -1)} · {talent.city}
          </Text>
          <PriceBadge rate={talent.rate} />
        </View>
      </Pressable>
      <SaveButton talent={talent} />
    </View>
  );
}
export function FeaturedTalentCard({ talent }: { talent: Talent }) {
  return <TalentCard talent={talent} featured />;
}
export function TalentGrid({
  data,
  header,
  empty,
}: {
  data: Talent[];
  header?: React.ReactElement;
  empty?: React.ReactElement;
}) {
  const { padding, columns } = useLayout();
  return (
    <FlatList
      key={columns}
      data={data}
      numColumns={columns}
      keyExtractor={(t) => t.id}
      keyboardShouldPersistTaps="handled"
      keyboardDismissMode="on-drag"
      contentContainerStyle={{ paddingHorizontal: padding, paddingTop: 8, paddingBottom: 24 }}
      columnWrapperStyle={columns === 2 ? { gap: 12 } : undefined}
      ListHeaderComponent={header}
      ListEmptyComponent={empty}
      renderItem={({ item }) => (
        <View
          style={{
            width: columns === 2 ? '48%' : '100%',
            flex: columns === 2 ? 1 : undefined,
            maxWidth: columns === 2 ? '50%' : '100%',
            marginBottom: 12,
          }}
        >
          <TalentCard talent={item} />
        </View>
      )}
    />
  );
}
export function SectionTitle({
  title,
  action,
  onPress,
}: {
  title: string;
  action?: string;
  onPress?: () => void;
}) {
  return (
    <View style={styles.row}>
      <Heading>{title}</Heading>
      {action && (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={action}
          onPress={onPress}
          style={{ minHeight: 48, minWidth: 48, justifyContent: 'center' }}
        >
          <Text style={styles.link}>{action} ›</Text>
        </Pressable>
      )}
    </View>
  );
}
export function PortfolioGrid({
  images,
  onOpen,
}: {
  images: { image: string; label: string }[];
  onOpen: (index: number) => void;
}) {
  return (
    <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 10 }}>
      {images.map((item, index) => (
        <Pressable
          key={`${item.image}-${index}`}
          accessibilityRole="button"
          accessibilityLabel={`Open ${item.label}`}
          onPress={() => onOpen(index)}
          style={{ width: '48%', flexGrow: 1, maxWidth: '50%' }}
        >
          <TalentImage
            source={talentMedia[item.image]}
            label={item.label}
            style={{ height: undefined, aspectRatio: 3 / 4, borderRadius: 14 }}
          />
        </Pressable>
      ))}
    </View>
  );
}
export function BookingCard({ booking, talent }: { booking: Booking; talent: Talent }) {
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`View ${booking.project} booking`}
      onPress={() => router.push(`/booking-detail/${booking.id}`)}
      style={styles.listCard}
    >
      <TalentImage
        source={talentMedia[talent.portrait]}
        label={`Sample portrait for ${talent.name}`}
        style={styles.thumbnail}
      />
      <View style={{ flex: 1, gap: 4 }}>
        <Text style={styles.cardName}>{talent.name}</Text>
        <Label style={{ color: c.text, fontSize: 12 }}>{booking.project}</Label>
        <Text style={styles.metadata}>
          {booking.date} · {booking.location}
        </Text>
      </View>
      <View
        style={[
          styles.status,
          {
            backgroundColor:
              booking.status === 'Confirmed'
                ? '#E4F4EB'
                : booking.status === 'Completed'
                  ? c.lavender
                  : '#FFF3DA',
          },
        ]}
      >
        <Text
          style={{
            fontFamily: f.medium,
            fontSize: 11,
            color: booking.status === 'Confirmed' ? c.success : c.primaryDark,
          }}
        >
          {booking.local ? 'Local draft' : booking.status}
        </Text>
      </View>
    </Pressable>
  );
}
export function MessageCard({
  conversation,
  talent,
}: {
  conversation: Conversation;
  talent: Talent;
}) {
  const { openConversation } = useDemo();
  const { fontScale } = useLayout();
  const unread = conversation.unread
    ? conversation.messages.filter((message) => !message.mine).length
    : 0;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`Open conversation with ${talent.name}`}
      accessibilityHint={`${unread ? `${unread} unread sample messages. ` : ''}${conversation.updatedLabel || 'Local draft'}. Opens a local-only demonstration.`}
      onPress={() => {
        openConversation(talent.id);
        router.push(`/chat/${talent.id}`);
      }}
      style={[styles.listCard, { minHeight: 76, paddingVertical: 10, paddingHorizontal: 12 }]}
    >
      <TalentImage
        source={talentMedia[talent.portrait]}
        label={`Sample portrait for ${talent.name}`}
        style={[styles.thumbnail, { width: 46, height: 46, borderRadius: 23 }]}
      />
      <View style={{ flex: 1, gap: 5 }}>
        <View style={{ flexDirection: 'row', alignItems: 'baseline', flexWrap: 'wrap', gap: 4 }}>
          <Text style={[styles.cardName, { flexGrow: 1 }]}>{talent.name}</Text>
          <Text style={{ color: c.secondary, fontFamily: f.medium, fontSize: 11 }}>
            {conversation.updatedLabel || 'Local draft'}
          </Text>
        </View>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
          <Text
            numberOfLines={fontScale > 1.3 ? 2 : 1}
            style={[
              styles.metadata,
              {
                flex: 1,
                color: unread ? c.text : c.secondary,
                fontFamily: unread ? f.semibold : f.regular,
              },
            ]}
          >
            {conversation.messages.at(-1)?.text || 'Start a local conversation'}
          </Text>
          {unread > 0 && (
            <View
              accessibilityLabel={`${unread} unread sample messages`}
              style={{
                minWidth: 23,
                minHeight: 23,
                paddingHorizontal: 6,
                paddingVertical: 2,
                borderRadius: 12,
                backgroundColor: c.primary,
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <Text style={{ color: c.white, fontFamily: f.bold, fontSize: 11 }}>{unread}</Text>
            </View>
          )}
        </View>
      </View>
    </Pressable>
  );
}
export function BottomNavigation({
  state,
  emitter,
  navigateToTab,
  descriptors,
}: BottomTabBarProps) {
  const insets = useSafeAreaInsets();
  const [keyboardOpen, setKeyboardOpen] = useState(false);
  useEffect(() => {
    const show = Keyboard.addListener('keyboardDidShow', () => setKeyboardOpen(true));
    const hide = Keyboard.addListener('keyboardDidHide', () => setKeyboardOpen(false));
    return () => {
      show.remove();
      hide.remove();
    };
  }, []);
  if (keyboardOpen) return null;
  const icons: Record<string, IconName> = {
    index: 'home-outline',
    search: 'search-outline',
    bookings: 'calendar-outline',
    messages: 'chatbubble-ellipses-outline',
    account: 'person-outline',
  };
  return (
    <View style={[styles.nav, { paddingBottom: Math.max(insets.bottom, 8) }]}>
      {state.routes.map((route, index) => {
        const focused = state.index === index;
        const title = descriptors[route.key].options.title || route.name;
        return (
          <Pressable
            key={route.key}
            accessibilityRole="tab"
            accessibilityLabel={title}
            accessibilityState={{ selected: focused }}
            aria-selected={focused}
            onPress={() => {
              const event = emitter.emit({
                type: 'tabPress',
                target: route.key,
                canPreventDefault: true,
              });
              if (!focused && !event.defaultPrevented) navigateToTab(route.key);
            }}
            style={styles.navItem}
          >
            <View style={[styles.navIcon, focused && { backgroundColor: c.lavender }]}>
              <Icon
                name={icons[route.name] || 'ellipse-outline'}
                color={focused ? c.primaryDark : c.secondary}
                size={21}
              />
            </View>
            <Text
              style={{
                fontFamily: focused ? f.bold : f.medium,
                fontSize: 11,
                color: focused ? c.primaryDark : c.secondary,
              }}
            >
              {title}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}
export function ChoiceModal({
  visible,
  title,
  options,
  onSelect,
  onClose,
}: {
  visible: boolean;
  title: string;
  options: string[];
  onSelect: (value: string) => void;
  onClose: () => void;
}) {
  const insets = useSafeAreaInsets();
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <View style={styles.modalShade}>
        <Pressable
          accessibilityLabel="Dismiss choices"
          onPress={onClose}
          style={StyleSheet.absoluteFill}
        />
        <View style={[styles.choiceSheet, { paddingBottom: Math.max(insets.bottom, 20) }]}>
          <View style={styles.row}>
            <Heading>{title}</Heading>
            <IconButton name="close" label="Close choices" onPress={onClose} />
          </View>
          <ScrollView>
            {options.map((option) => (
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={option}
                key={option}
                onPress={() => {
                  onSelect(option);
                  onClose();
                }}
                style={styles.choice}
              >
                <Label style={{ color: c.text }}>{option}</Label>
                <Icon name="chevron-forward" size={16} color={c.primary} />
              </Pressable>
            ))}
          </ScrollView>
        </View>
      </View>
    </Modal>
  );
}
export function Field({
  label,
  error,
  ...props
}: React.ComponentProps<typeof TextInput> & { label: string; error?: string }) {
  return (
    <View style={{ gap: 7 }}>
      <Text style={styles.fieldLabel}>{label}</Text>
      <TextInput
        {...props}
        accessibilityLabel={label}
        placeholderTextColor={c.secondary}
        style={[
          styles.field,
          props.multiline && { minHeight: 110, textAlignVertical: 'top' },
          error && { borderColor: c.error },
        ]}
      />
      {error && (
        <Text
          accessibilityRole="alert"
          style={{ color: c.error, fontFamily: f.medium, fontSize: 12 }}
        >
          {error}
        </Text>
      )}
    </View>
  );
}
export const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: c.background },
  body: { fontFamily: f.regular, fontSize: 13, color: c.secondary, lineHeight: 21 },
  heading: { fontFamily: f.bold, color: c.text, fontSize: 17, lineHeight: 25 },
  sample: { fontFamily: f.medium, color: c.primaryDark, fontSize: 11, lineHeight: 16 },
  topbar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 8,
    minHeight: 58,
    borderBottomWidth: 1,
    borderColor: c.border,
    marginBottom: 8,
  },
  topTitle: { flex: 1, textAlign: 'center', fontFamily: f.bold, fontSize: 16, color: c.text },
  iconButton: {
    minHeight: 48,
    minWidth: 48,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 22,
  },
  primary: {
    minHeight: 48,
    paddingHorizontal: 18,
    paddingVertical: 12,
    backgroundColor: c.primary,
    borderRadius: 24,
    alignItems: 'center',
    justifyContent: 'center',
    flexDirection: 'row',
    gap: 8,
  },
  secondaryButton: { backgroundColor: c.white, borderWidth: 1, borderColor: c.primary },
  buttonText: {
    color: c.white,
    fontFamily: f.bold,
    fontSize: 13,
    flexShrink: 1,
    textAlign: 'center',
  },
  chip: {
    minHeight: 48,
    minWidth: 48,
    borderWidth: 1,
    borderColor: c.border,
    borderRadius: 22,
    paddingHorizontal: 13,
    paddingVertical: 9,
    alignItems: 'center',
    justifyContent: 'center',
    flexDirection: 'row',
    gap: 5,
    backgroundColor: '#F6F2FF',
  },
  chipActive: { backgroundColor: c.primary, borderColor: c.primary },
  chipText: { fontFamily: f.medium, fontSize: 11, color: c.text },
  searchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: c.white,
    borderRadius: 28,
    borderWidth: 1,
    borderColor: c.border,
    paddingHorizontal: 10,
    minHeight: 48,
  },
  search: { flexDirection: 'row', alignItems: 'center', gap: 8, flex: 1 },
  input: {
    flex: 1,
    minWidth: 0,
    minHeight: 48,
    color: c.text,
    fontFamily: f.regular,
    fontSize: 13,
    paddingVertical: 8,
  },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8 },
  footer: {
    backgroundColor: c.white,
    borderTopWidth: 1,
    borderColor: c.border,
    paddingHorizontal: 16,
    paddingTop: 12,
  },
  empty: { paddingVertical: 32, paddingHorizontal: 16, alignItems: 'center', gap: 14 },
  emptyIcon: {
    width: 64,
    height: 64,
    borderRadius: 32,
    backgroundColor: c.lavender,
    alignItems: 'center',
    justifyContent: 'center',
  },
  card: {
    backgroundColor: c.white,
    borderWidth: 1,
    borderColor: c.border,
    borderRadius: 14,
    overflow: 'hidden',
    ...theme.shadow,
  },
  cardBody: { padding: 9, gap: 5 },
  cardName: { fontFamily: f.bold, color: c.text, fontSize: 13, lineHeight: 19 },
  metadata: { fontFamily: f.regular, color: c.secondary, fontSize: 11, lineHeight: 16 },
  priceBadge: {
    backgroundColor: c.lavender,
    paddingHorizontal: 6,
    paddingVertical: 4,
    borderRadius: 8,
    alignSelf: 'flex-start',
  },
  price: { fontFamily: f.bold, fontSize: 11, color: c.primaryDark, lineHeight: 16 },
  day: { fontFamily: f.medium, fontSize: 11 },
  saveTarget: {
    position: 'absolute',
    top: 0,
    right: 0,
    width: 48,
    height: 48,
    alignItems: 'center',
    justifyContent: 'center',
  },
  saveCircle: {
    width: 28,
    height: 28,
    backgroundColor: c.white,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  link: { color: c.primaryDark, fontFamily: f.semibold, fontSize: 12 },
  nav: {
    flexDirection: 'row',
    backgroundColor: '#FFFFFFF5',
    borderTopWidth: 1,
    borderColor: c.border,
    paddingTop: 5,
  },
  navItem: { flex: 1, minHeight: 54, alignItems: 'center', justifyContent: 'center', gap: 2 },
  navIcon: {
    width: 40,
    height: 28,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  listCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    padding: 12,
    borderRadius: 16,
    backgroundColor: c.white,
    borderWidth: 1,
    borderColor: c.border,
    minHeight: 86,
  },
  thumbnail: { width: 56, height: 64, borderRadius: 12, aspectRatio: undefined },
  status: { paddingHorizontal: 7, paddingVertical: 6, borderRadius: 14, maxWidth: 84 },
  dot: { width: 9, height: 9, borderRadius: 5, backgroundColor: c.primary },
  modalShade: { flex: 1, backgroundColor: '#241B3866', justifyContent: 'flex-end' },
  choiceSheet: {
    backgroundColor: c.background,
    padding: 20,
    borderTopLeftRadius: 24,
    borderTopRightRadius: 24,
    maxHeight: '75%',
    width: '100%',
    maxWidth: 600,
    alignSelf: 'center',
  },
  choice: {
    minHeight: 50,
    borderBottomWidth: 1,
    borderColor: c.border,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  field: {
    borderWidth: 1,
    borderColor: c.border,
    backgroundColor: '#F7F4FC',
    borderRadius: 12,
    minHeight: 48,
    padding: 12,
    fontFamily: f.regular,
    fontSize: 13,
    color: c.text,
  },
  fieldLabel: { fontFamily: f.semibold, fontSize: 12, color: c.text },
});
