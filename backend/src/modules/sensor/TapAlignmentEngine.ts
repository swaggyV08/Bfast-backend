import { logger } from '@/shared/utils/logger';
import type { BatchSensorReadingsInput } from './sensor.schemas';

// In-memory buffer for tap alignment.
// Key: sessionId -> deviceId -> readings array
const sessionBuffers = new Map<string, Map<string, any[]>>();

// Status tracker for sessions
export const sessionStatuses = new Map<string, string>(); // sessionId -> 'PENDING' | 'TAP_CONFIRMED'

export class TapAlignmentEngine {
  
  static processBatch(
    deviceId: string,
    input: BatchSensorReadingsInput,
    correlationId: string
  ) {
    const { sessionId, role, peerDeviceId, readings } = input;
    
    // Initialize session buffers
    if (!sessionBuffers.has(sessionId)) {
      sessionBuffers.set(sessionId, new Map());
      sessionStatuses.set(sessionId, 'PENDING');
    }
    const sessionMap = sessionBuffers.get(sessionId)!;
    
    // Initialize device buffer
    if (!sessionMap.has(deviceId)) {
      sessionMap.set(deviceId, []);
    }
    const deviceBuffer = sessionMap.get(deviceId)!;
    
    // Append and trim to last 500 samples (~2.5s at 200Hz)
    deviceBuffer.push(...readings);
    if (deviceBuffer.length > 500) {
      deviceBuffer.splice(0, deviceBuffer.length - 500);
    }
    
    // If we are the sender, check if the receiver has recent data to align
    if (role === 'sender' && peerDeviceId) {
      const receiverBuffer = sessionMap.get(peerDeviceId);
      if (receiverBuffer && receiverBuffer.length > 50) { // Need at least some overlap
        this.attemptAlignment(sessionId, deviceId, peerDeviceId, deviceBuffer, receiverBuffer, correlationId);
      }
    }
  }

  private static attemptAlignment(
    sessionId: string,
    senderId: string,
    receiverId: string,
    senderBuffer: any[],
    receiverBuffer: any[],
    correlationId: string
  ) {
    if (sessionStatuses.get(sessionId) === 'TAP_CONFIRMED') {
      return; // Already confirmed
    }

    // Look for a peak in sender
    const senderPeak = this.findPeak(senderBuffer);
    if (!senderPeak) return;

    // Look for a peak in receiver
    const receiverPeak = this.findPeak(receiverBuffer);
    if (!receiverPeak) return;

    // Calculate time difference between peaks based on recordedAt (ISO string)
    const tSender = new Date(senderPeak.recordedAt).getTime();
    const tReceiver = new Date(receiverPeak.recordedAt).getTime();
    const diffMs = Math.abs(tSender - tReceiver);

    // If peaks are within 100ms of each other, it's a mutual tap!
    // We also check gyro to ensure they weren't just waving the phone.
    if (diffMs <= 100 && senderPeak.gyroMagnitude < 2.0 && receiverPeak.gyroMagnitude < 2.0) {
      logger.info('Mutual Tap Aligned on Server!', {
        correlationId,
        sessionId,
        senderId,
        receiverId,
        diffMs,
        senderAccel: senderPeak.accelMagnitude,
        receiverAccel: receiverPeak.accelMagnitude
      });
      
      sessionStatuses.set(sessionId, 'TAP_CONFIRMED');
      
      // Cleanup buffers
      sessionBuffers.delete(sessionId);
    }
  }

  private static findPeak(buffer: any[]) {
    let peak = null;
    let maxAccel = 0;
    
    // Soft tap threshold: ~2.0 m/s^2 above gravity
    for (const reading of buffer) {
      if (reading.accelMagnitude > maxAccel && reading.accelMagnitude > 2.0) {
        maxAccel = reading.accelMagnitude;
        peak = reading;
      }
    }
    return peak;
  }
}
