import { NativeModules } from 'react-native';

type CameraResult = {
  action: 'save' | 'edit' | 'error';
  ok: boolean;
  mediaPath?: string;
  receiptPath?: string;
  message?: string;
};

const { CameraAttest } = NativeModules as {
  CameraAttest: { openCamera: () => Promise<CameraResult> }
};

export async function openCamera(): Promise<CameraResult> {
  return CameraAttest.openCamera();
}