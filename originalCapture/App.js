import { StatusBar } from 'expo-status-bar';
import React from 'react';
import { useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View, Platform, ScrollView } from 'react-native';
import { openCamera } from './native/CameraAttest';

export default function App() {
  const [result, setResult] = useState(null);
  const launchedRef = useRef(false);

  useEffect(() => {
    // Avoid double-launch on fast refresh / strict mode
    if (launchedRef.current) return;
    launchedRef.current = true;

    (async () => {
      try {
        // Immediately open native camera on startup
        const res = await openCamera();
        setResult(res);

        // Example: you can branch here if you want to chain flows automatically
        // if (res.action === 'save' && res.ok) {
        //   await upload(res.mediaPath, res.receiptPath);
        // } else if (res.action === 'edit' && res.mediaPath) {
        //   navigateToEditor(res.mediaPath); // your RN editor screen
        // }
      } catch (e) {
        setResult({ action: 'error', ok: false, message: String(e) });
      }
    })();
  }, []);

  return (
    <View style={styles.container}>
      <Text style={styles.title}>
        {Platform.OS === 'android'
          ? 'Launching native camera…'
          : 'Android only: native camera module'}
      </Text>

      <ScrollView style={styles.result} contentContainerStyle={{ padding: 12 }}>
        <Text style={styles.heading}>Last Result</Text>
        <Text selectable>{JSON.stringify(result, null, 2)}</Text>
      </ScrollView>

      <StatusBar style="auto" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#fff', paddingTop: 64, paddingHorizontal: 20 },
  title: { fontSize: 18, fontWeight: '600', marginBottom: 16, textAlign: 'center' },
  heading: { fontSize: 16, fontWeight: '500', marginBottom: 8 },
  result: { marginTop: 16, width: '100%' },
});
