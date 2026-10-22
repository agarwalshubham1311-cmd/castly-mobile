import React, { useEffect, useRef, useState } from 'react';
import { ScrollView, Text, TextInput, View } from 'react-native';
import { useLocalSearchParams } from 'expo-router';
import { useDemo } from '../store';
import { talents } from '../talent';
import { c, f } from '../theme';
import { EmptyState, IconButton, PrimaryButton, SampleNote, Screen } from '../components/ui';
export default function Chat() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { conversations, openConversation, sendMessage, archiveConversation } = useDemo();
  const talent = talents.find((t) => t.id === id);
  const conversation = conversations.find((conv) => conv.id === id);
  const [text, setText] = useState('');
  const scroll = useRef<React.ComponentRef<typeof ScrollView>>(null);
  useEffect(() => {
    if (talent && (!conversation || conversation.unread)) openConversation(talent.id);
  }, [talent, conversation, openConversation]);
  if (!talent)
    return (
      <Screen title="Conversation">
        <EmptyState
          title="Conversation not found"
          detail="Please open a conversation from a sample profile."
        />
      </Screen>
    );
  const send = () => {
    if (text.trim()) {
      sendMessage(id, text);
      setText('');
    }
  };
  return (
    <Screen
      title={talent.name}
      scroll={false}
      action={
        <IconButton
          name={conversation?.archived ? 'archive' : 'archive-outline'}
          label={conversation?.archived ? 'Unarchive conversation' : 'Archive conversation'}
          onPress={() => archiveConversation(id)}
        />
      }
      footer={
        <View style={{ flexDirection: 'row', gap: 8, alignItems: 'flex-end' }}>
          <TextInput
            accessibilityLabel="Local message"
            value={text}
            onChangeText={setText}
            multiline
            placeholder="Write a local message…"
            placeholderTextColor={c.secondary}
            style={{
              flex: 1,
              maxHeight: 120,
              minHeight: 48,
              borderWidth: 1,
              borderColor: c.border,
              borderRadius: 18,
              padding: 12,
              color: c.text,
              fontFamily: f.regular,
              fontSize: 13,
            }}
          />
          <PrimaryButton title="Send locally" disabled={!text.trim()} onPress={send} />
        </View>
      }
    >
      <View style={{ paddingHorizontal: 20, paddingVertical: 8 }}>
        <SampleNote text="Demo chat · messages stay in this session and are not delivered" />
      </View>
      <ScrollView
        ref={scroll}
        onContentSizeChange={() => scroll.current?.scrollToEnd({ animated: false })}
        contentContainerStyle={{ padding: 20, gap: 14, flexGrow: 1 }}
      >
        {conversation?.messages.map((message) => (
          <View
            key={message.id}
            style={{
              backgroundColor: message.mine ? c.primaryDark : c.white,
              borderWidth: 1,
              borderColor: message.mine ? c.primaryDark : c.border,
              padding: 14,
              maxWidth: '88%',
              borderRadius: 18,
              alignSelf: message.mine ? 'flex-end' : 'flex-start',
            }}
          >
            <Text
              style={{
                fontFamily: f.regular,
                color: message.mine ? c.white : c.text,
                fontSize: 13,
                lineHeight: 21,
              }}
            >
              {message.text}
            </Text>
            <Text
              style={{
                fontFamily: f.medium,
                fontSize: 11,
                color: message.mine ? '#EDE5FF' : c.secondary,
                marginTop: 7,
              }}
            >
              {message.mine ? 'Local only · not sent' : 'Sample conversation'}
            </Text>
          </View>
        ))}
        {!conversation?.messages.length && (
          <EmptyState
            title="Start a creative conversation"
            detail="Try a message below. This is a local-only demonstration with no recipient or automated replies."
          />
        )}
      </ScrollView>
    </Screen>
  );
}
