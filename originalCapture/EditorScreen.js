import React from 'react';
import { Text, View, Button } from 'react-native';

export default function EditorScreen({ route, navigation }) {
  const { mediaPath } = route?.params ?? {};
  return (
    <View style={{ flex: 1, padding: 20, paddingTop: 64 }}>
      <Text style={{ fontSize: 18, fontWeight: '600' }}>Editor</Text>
      <Text style={{ marginTop: 12 }}>mediaPath:</Text>
      <Text selectable>{mediaPath ?? '(none)'}</Text>
      <View style={{ height: 16 }} />
      <Button title="Back" onPress={() => navigation.goBack()} />
    </View>
  );
}