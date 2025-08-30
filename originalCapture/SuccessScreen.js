import { View, Text, Button } from "react-native";

export default function SuccessScreen({ route, navigation }) {
  const { details } = route.params || {};
  return (
    <View style={{ flex: 1, alignItems: "center", justifyContent: "center" }}>
      <Text style={{ fontSize: 24, fontWeight: "bold", marginBottom: 12 }}>
        ✅ GOOD JOB — its legit!
      </Text>
      <Text>{JSON.stringify(details, null, 2)}</Text>
      <Button title="Back" onPress={() => navigation.goBack()} />
    </View>
  );
}