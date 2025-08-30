// EditorScreen.js
import React, { useMemo, useRef, useState } from 'react';
import {
  View, Text, Image, Button, StyleSheet, ActivityIndicator,
  TouchableOpacity, Alert, Platform, Dimensions, Animated
} from 'react-native';
import { cropAndOverwriteOriginalExternalFiles } from './cropHelpers';
import { NativeModules } from 'react-native';
const { AttestationBridge } = NativeModules;

const { width: SCREEN_W, height: SCREEN_H } = Dimensions.get('window');
const PADDING = 16;
const BASE_URL = 'http://192.168.253.197:8000'; // your FastAPI server

export default function EditorScreen({ route, navigation }) {
  const { mediaPath, returnTo } = route?.params ?? {};
  const [loading, setLoading] = useState(true);
  const [rotateDeg, setRotateDeg] = useState(0);
  const [imgVersion, setImgVersion] = useState(0);

  const scale = useRef(new Animated.Value(1)).current;
  const lastTapRef = useRef(0);
  const [zoomed, setZoomed] = useState(false);

  const imageUri = useMemo(() => {
    if (!mediaPath) return undefined;
    const raw = mediaPath.startsWith('file://') ? mediaPath.slice(7) : mediaPath;
    return `file://${raw}?v=${imgVersion}`;
  }, [mediaPath, imgVersion]);

  const onImageLoadEnd = () => setLoading(false);

  const onDoubleTap = () => {
    const now = Date.now();
    if (now - (lastTapRef.current || 0) < 250) {
      Animated.spring(scale, { toValue: zoomed ? 1 : 2, useNativeDriver: true }).start(() =>
        setZoomed(!zoomed)
      );
    }
    lastTapRef.current = now;
  };

  const onRotate = () => setRotateDeg((d) => (d + 90) % 360);

    const onCrop = async () => {
    try {
        if (!mediaPath) return;
        await cropAndOverwriteOriginalExternalFiles(mediaPath, { width: 1080, height: 1080 });
        setImgVersion(v => v + 1); // refresh same file
    } catch (e) {
        const msg = e?.message ? String(e.message) : String(e);
        if (/cancel/i.test(msg)) return;
        Alert.alert('Crop failed', msg);
        console.log('[CROP][ERROR]', msg);
    }
    };

  const onCompose = () => Alert.alert('Compose (placeholder)', 'Hook this up to draw/markup flow.');
  const onRedact  = () => Alert.alert('Redact (placeholder)', 'Hook this to adjustments, etc.');

 const onSave = async () => {
    try {
      if (!mediaPath) {
        return Alert.alert('Nothing to save', 'Missing mediaPath');
      }

      const res = await AttestationBridge.saveFromEditor(
        mediaPath,
        BASE_URL
      );
      // res: { ok, mediaPath, receiptPath, message, serverCode, serverBody }
      let verified = false;
      try {
        const body = JSON.parse(res.serverBody);
        verified =
          body?.ok === true &&
          body?.message === 'verified' &&
          body?.attestation?.attestationSecurityLevel === 1;
      } catch (e) {
        // keep verified = false
      }

      if (verified) {
        navigation.navigate('SuccessScreen', { details: JSON.parse(res.serverBody) });
      } else {
        Alert.alert('Verification failed', res.serverBody || `HTTP ${res.serverCode}`);
      }
    } catch (e) {
      Alert.alert('Error', String(e?.message || e));
    }
  };

  const onDiscard = () =>
    Alert.alert('Discard changes?', 'This will lose current edits.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Discard', style: 'destructive', onPress: () => navigation.goBack() },
    ]);

  const onBack = () => navigation.goBack();

  return (
    <View style={styles.container}>
      {/* Header */}
      <View style={styles.header}>
        <Text style={styles.headerTitle}>Editor</Text>
        <View style={styles.headerRight}>
          <Button title="Back" onPress={onBack} />
        </View>
      </View>

      {/* Info row */}
      <View style={styles.infoRow}>
        <Text numberOfLines={1} style={styles.pathText}>
          {mediaPath || '(no media)'}
        </Text>
      </View>

      {/* Canvas */}
      <View style={styles.canvas}>
        {imageUri ? (
          <>
            {loading && (
              <View style={styles.loading}>
                <ActivityIndicator />
                <Text style={{ marginTop: 8, color: '#ccc' }}>Loading image…</Text>
              </View>
            )}
            <Animated.View style={{ transform: [{ scale }, { rotate: `${rotateDeg}deg` }] }}>
              <TouchableOpacity activeOpacity={1} onPress={onDoubleTap}>
                <Image
                  source={{ uri: imageUri }}
                  onLoadEnd={onImageLoadEnd}
                  resizeMode="contain"
                  style={styles.image}
                />
              </TouchableOpacity>
            </Animated.View>
          </>
        ) : (
          <View style={styles.empty}>
            <Text style={{ color: '#999' }}>No mediaPath provided.</Text>
          </View>
        )}
      </View>

      {/* Toolbar */}
      <View style={styles.toolbar}>
        <ToolButton label="Rotate" onPress={onRotate} />
        <ToolButton label="Crop" onPress={onCrop} />
        <ToolButton label="Compose" onPress={onCompose} />
        <ToolButton label="Redact" onPress={onRedact} />
      </View>

      {/* Footer */}
      <View style={styles.footer}>
        <TouchableOpacity style={[styles.cta, styles.discard]} onPress={onDiscard}>
          <Text style={styles.ctaText}>Discard</Text>
        </TouchableOpacity>
        <TouchableOpacity style={[styles.cta, styles.save]} onPress={onSave}>
          <Text style={[styles.ctaText, styles.ctaTextDark]}>Save</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

function ToolButton({ label, onPress }) {
  return (
    <TouchableOpacity onPress={onPress} style={styles.toolBtn}>
      <Text style={styles.toolLabel}>{label}</Text>
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#0b0b0b' },
  header: {
    paddingTop: Platform.OS === 'android' ? 24 : 12,
    paddingHorizontal: PADDING,
    paddingBottom: 12,
    flexDirection: 'row',
    alignItems: 'center',
  },
  headerTitle: { flex: 1, color: 'white', fontSize: 18, fontWeight: '700' },
  headerRight: { marginLeft: 8 },
  infoRow: { paddingHorizontal: PADDING, paddingBottom: 8 },
  pathText: { color: '#aaa', fontSize: 12 },
  canvas: {
    flex: 1, alignItems: 'center', justifyContent: 'center',
    marginHorizontal: PADDING, backgroundColor: '#111', borderRadius: 12, overflow: 'hidden',
  },
  image: { width: SCREEN_W - PADDING * 2, height: SCREEN_H * 0.55 },
  loading: { position: 'absolute', alignItems: 'center', justifyContent: 'center' },
  empty: { alignItems: 'center', justifyContent: 'center' },
  toolbar: {
    flexDirection: 'row', justifyContent: 'space-around',
    paddingHorizontal: PADDING, paddingVertical: 12,
    borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: '#222', backgroundColor: '#0f0f0f',
  },
  toolBtn: { paddingHorizontal: 12, paddingVertical: 10, borderRadius: 8, backgroundColor: '#1b1b1b' },
  toolLabel: { color: 'white', fontSize: 14, fontWeight: '600' },
  footer: {
    flexDirection: 'row', justifyContent: 'space-between',
    paddingHorizontal: PADDING, paddingTop: 8, paddingBottom: PADDING, backgroundColor: '#0b0b0b',
  },
  cta: { flex: 1, marginHorizontal: 6, alignItems: 'center', paddingVertical: 14, borderRadius: 10 },
  discard: { backgroundColor: '#2a2a2a' },
  save: { backgroundColor: '#f2f2f2' },
  ctaText: { color: 'white', fontSize: 16, fontWeight: '700' },
  ctaTextDark: { color: '#0b0b0b' },
});
