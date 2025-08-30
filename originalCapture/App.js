import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Text, View, Button, ScrollView, Platform, StyleSheet } from 'react-native';
import { NavigationContainer, useFocusEffect } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { InteractionManager } from 'react-native';
import { openCamera } from './native/CameraAttest';
import EditorScreen from './EditorScreen';
import ProvenanceTestUI from './ProvenanceTestUI';

const Stack = createNativeStackNavigator();

function HomeScreen({ navigation }) {
  const [result, setResult] = useState(null);
  const [showProvenanceTest, setShowProvenanceTest] = useState(false);
  const launchedRef = useRef(false);

  const onLaunch = async () => {
    try {
      const res = await openCamera();
      setResult(res);

      if (res?.action === 'edit' && res?.mediaPath) {
        navigation.navigate('Editor', { mediaPath: res.mediaPath });
      }

    } catch (e) {
      setResult({ action: 'error', ok: false, message: String(e) });
    }
  };

  useFocusEffect(
    useCallback(() => {
      if (launchedRef.current) return;
      launchedRef.current = true;

      let cancelled = false;
      const task = InteractionManager.runAfterInteractions(async () => {
        if (cancelled) return;
        await onLaunch();
      });
      return () => {
        cancelled = true;
        task.cancel?.();
      };
    }, [])
  );

  if (showProvenanceTest) {
    return <ProvenanceTestUI onBack={() => setShowProvenanceTest(false)} />;
  }

  return (
    <View style={styles.container}>
      <Text style={styles.title}>
        {Platform.OS === 'android' ? 'Native camera demo' : 'Android only'}
      </Text>

      <View style={styles.buttonContainer}>
        <Button title="Open Camera Now" onPress={onLaunch} />
        <View style={styles.spacer} />
        <Button
          title="Test Provenance System"
          onPress={() => setShowProvenanceTest(true)}
          color="#28a745"
        />
      </View>

      <ScrollView style={styles.resultContainer}>
        <Text style={styles.resultTitle}>Last Result</Text>
        <Text selectable style={styles.resultText}>
          {JSON.stringify(result, null, 2)}
        </Text>
      </ScrollView>
    </View>
  );
}

export default function App() {
  return (
    <NavigationContainer>
      <Stack.Navigator>
        <Stack.Screen name="Home" component={HomeScreen} options={{ title: 'Camera Attest' }} />
        <Stack.Screen name="Editor" component={EditorScreen} options={{ title: 'Edit Photo' }} />
      </Stack.Navigator>
    </NavigationContainer>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 20,
    paddingTop: 64,
    backgroundColor: '#fff',
  },
  title: {
    fontSize: 18,
    fontWeight: '600',
    textAlign: 'center',
    marginBottom: 20,
  },
  buttonContainer: {
    marginBottom: 20,
  },
  spacer: {
    height: 10,
  },
  resultContainer: {
    flex: 1,
    borderWidth: 1,
    borderColor: '#ddd',
    borderRadius: 8,
    padding: 12,
    backgroundColor: '#f9f9f9',
  },
  resultTitle: {
    fontWeight: '500',
    marginBottom: 8,
    fontSize: 16,
  },
  resultText: {
    fontSize: 12,
    fontFamily: Platform.OS === 'android' ? 'monospace' : 'Menlo',
  },
});