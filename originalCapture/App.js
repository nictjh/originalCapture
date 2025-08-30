import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Text, View, Button, ScrollView, Platform } from 'react-native';
import { NavigationContainer, useFocusEffect } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { InteractionManager } from 'react-native';
import { openCamera } from './native/CameraAttest';
import EditorScreen from './EditorScreen';
import SuccessScreen from './SuccessScreen';

const Stack = createNativeStackNavigator();

function HomeScreen({ navigation }) {
  const [result, setResult] = useState(null);
  const launchedRef = useRef(false);

  const onLaunch = async () => {
    try {
      const res = await openCamera();
      setResult(res);

       if (res?.serverResponseBody) {
        try {
            const serverJson = JSON.parse(res.serverResponseBody);

            if (
            serverJson.ok === true &&
            serverJson.message === "verified" &&
            serverJson.attestation?.attestationSecurityLevel === 1
            ) {
            // ✅ Strict success case
            navigation.navigate("SuccessScreen", { details: serverJson });
            return;
            }
        } catch (e) {
            console.warn("Failed to parse serverResponseBody", e);
        }
        }

      //Navigate Button use the result bro, please reference my returns for picture
      if (res?.action === 'edit' && res?.mediaPath) {
        navigation.navigate('Editor', { mediaPath: res.mediaPath });
      }

    } catch (e) {
      setResult({ action: 'error', ok: false, message: String(e) });
    }
  };

  // Auto-launch when screen first becomes focused (not during splash)
  useFocusEffect(
    useCallback(() => {
      if (launchedRef.current) return;
      launchedRef.current = true;

      let cancelled = false;
      // Wait until all interactions complete to ensure Activity is ready
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

  return (
    <View style={{ flex: 1, padding: 20, paddingTop: 64 }}>
      <Text style={{ fontSize: 18, fontWeight: '600', textAlign: 'center' }}>
        {Platform.OS === 'android' ? 'Native camera demo' : 'Android only'}
      </Text>

      <View style={{ marginTop: 16 }}>
        <Button title="Open Camera Now" onPress={onLaunch} />
      </View>

      <ScrollView style={{ marginTop: 16 }}>
        <Text style={{ fontWeight: '500', marginBottom: 8 }}>Last Result</Text>
        <Text selectable>{JSON.stringify(result, null, 2)}</Text>
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
        <Stack.Screen name="SuccessScreen" component={SuccessScreen} options={{ title: 'Success' }} />
      </Stack.Navigator>
    </NavigationContainer>
  );
}
